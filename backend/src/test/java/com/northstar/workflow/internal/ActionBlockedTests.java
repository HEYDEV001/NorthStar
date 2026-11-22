package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.FakeProvider;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.DependentActions;
import com.northstar.workflow.DependentActions.Dependents;
import com.northstar.workflow.NewAction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The blocked failure class and {@link DependentActions} (step 2.8c, W34; workflow-spec.md section 10) against real
 * Postgres with RLS, on the 1.11 fake action types: a blocked attempt pauses without using an attempt or alerting; a
 * reconnect resumes auth pauses only and unblocking blocked ones only; blocking keeps a pending action's time, and
 * unblocking resumes it then and queues it; failing reaches the closing hook, the alert and Failed Actions; P1, P9, P10
 * and the database's check.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActionBlockedTests {

    private static final String CODE = "template_paused";
    private static final String FIELD = "messageId";

    private final Actions actions;
    private final DependentActions dependents;
    private final ExecuteActionHandler executeAction;
    private final FakeProvider provider;
    private final RecordingActionAlerts alerts;
    private final ClosedActionRecorder closed;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    ActionBlockedTests(
            @Autowired Actions actions,
            @Autowired DependentActions dependents,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeProvider provider,
            @Autowired RecordingActionAlerts alerts,
            @Autowired ClosedActionRecorder closed,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.actions = actions;
        this.dependents = dependents;
        this.executeAction = executeAction;
        this.provider = provider;
        this.alerts = alerts;
        this.closed = closed;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @BeforeEach
    void runLikeAWorkerThread() {
        RequestContextHolder.resetRequestAttributes();
    }

    // The blocked class

    @Test
    void W34_aBlockedAttemptPausesWithoutUsingAnAttemptOrRaisingAnAlert() {
        UUID tenant = data.tenant();
        Created action = create(tenant, FakeActionHandlers.TYPE);
        provider.script(
                action.key(),
                FakeCall.serverError(503),
                FakeCall.serverError(503),
                new FakeCall.Fail((ActionOutcome.Failed) ActionOutcome.blocked(CODE)));

        run(tenant, action.id());
        makeDue(action.id());
        run(tenant, action.id());
        makeDue(action.id());
        run(tenant, action.id());

        assertThat(state(action.id()))
                .as("the blocked third attempt is given back")
                .isEqualTo("paused|2|blocked|template_paused");
        assertThat(nextAttemptAtIsNull(action.id())).isTrue();
        assertThat(alerts.of(action.id()))
                .as("no auth alert, and no alert for a third attempt that did not count")
                .isEmpty();
        run(tenant, action.id());
        assertThat(provider.calls(action.key()))
                .as("a paused action does not run")
                .isEqualTo(3);
    }

    @Test
    void aReconnectResumesAuthPausesOnlyAndUnblockingResumesBlockedOnesOnly() {
        UUID tenant = data.tenant();
        Created auth = create(tenant, FakeActionHandlers.TYPE);
        Created blocked = create(tenant, FakeActionHandlers.TYPE);
        Created blockedOtherwise = create(tenant, FakeActionHandlers.TYPE);
        provider.script(auth.key(), FakeCall.authExpired());
        provider.script(blocked.key(), new FakeCall.Fail((ActionOutcome.Failed) ActionOutcome.blocked(CODE)));
        provider.script(
                blockedOtherwise.key(), new FakeCall.Fail((ActionOutcome.Failed) ActionOutcome.blocked("other_code")));
        run(tenant, auth.id());
        run(tenant, blocked.id());
        run(tenant, blockedOtherwise.id());

        assertThat(SystemScope.call(tenant, () -> actions.resumePaused(FakeActionHandlers.TYPE)))
                .isOne();
        assertThat(state(auth.id())).startsWith("pending|0|auth|");
        assertThat(state(blocked.id())).isEqualTo("paused|0|blocked|template_paused");

        admin.sql("update northstar.actions set status = 'paused', next_attempt_at = null where id = :id")
                .param("id", auth.id())
                .update();
        assertThat(system(tenant, () -> dependents.unblock(of(auth, blocked, blockedOtherwise), CODE)))
                .isOne();
        assertThat(state(blocked.id())).isEqualTo("pending|0|blocked|template_paused");
        assertThat(state(auth.id())).as("an auth pause waits for its reconnect").startsWith("paused|0|auth|");
        assertThat(state(blockedOtherwise.id()))
                .as("a block with another code waits for its own")
                .isEqualTo("paused|0|blocked|other_code");

        run(tenant, blocked.id());
        assertThat(state(blocked.id())).startsWith("succeeded|1|");
    }

    // Block, unblock, fail

    @Test
    void W34_blockingPausesPendingOnesKeepingTheirTimeAndLeavesTheRest() {
        UUID tenant = data.tenant();
        Created due = create(tenant, FakeActionHandlers.TYPE);
        Created later = create(tenant, FakeActionHandlers.TYPE);
        admin.sql("update northstar.actions set next_attempt_at = now() + interval '2 hours' where id = :id")
                .param("id", later.id())
                .update();
        Created waitingForRetry = create(tenant, FakeActionHandlers.TYPE);
        provider.script(waitingForRetry.key(), FakeCall.serverError(503));
        run(tenant, waitingForRetry.id());
        Created review = createForReview(tenant);
        Created processing = create(tenant, FakeActionHandlers.TYPE);
        admin.sql("""
                        update northstar.actions set status = 'processing', attempts = 1, next_attempt_at = null,
                          lease_expires_at = now() + interval '5 minutes' where id = :id""").param("id", processing.id()).update();
        Created otherType = create(tenant, FakeActionHandlers.QUICK_TYPE);
        Created notNamed = create(tenant, FakeActionHandlers.TYPE);
        String laterTime = nextAttemptAt(later.id());
        String retryTime = nextAttemptAt(waitingForRetry.id());

        int blocked = system(
                tenant,
                () -> dependents.block(
                        new Dependents(
                                FakeActionHandlers.TYPE,
                                FIELD,
                                values(due, later, waitingForRetry, review, processing, otherType)),
                        CODE));

        assertThat(blocked).isEqualTo(3);
        assertThat(state(due.id())).isEqualTo("paused|0|blocked|template_paused");
        assertThat(state(later.id())).isEqualTo("paused|0|blocked|template_paused");
        assertThat(state(waitingForRetry.id())).isEqualTo("paused|1|blocked|template_paused");
        assertThat(nextAttemptAt(later.id())).as("the time it was due is kept").isEqualTo(laterTime);
        assertThat(nextAttemptAt(waitingForRetry.id())).isEqualTo(retryTime);
        assertThat(state(review.id())).as("waits for its approval").isEqualTo("needs_review|0||");
        assertThat(state(processing.id())).as("its attempt decides").isEqualTo("processing|1||");
        assertThat(state(otherType.id())).isEqualTo("pending|0||");
        assertThat(state(notNamed.id())).isEqualTo("pending|0||");

        run(tenant, due.id());
        assertThat(provider.calls(due.key()))
                .as("a blocked action does not run")
                .isZero();
    }

    @Test
    void W34_unblockingResumesAtTheLaterOfTheKeptTimeAndNowAndQueuesIt() {
        UUID tenant = data.tenant();
        Created due = create(tenant, FakeActionHandlers.TYPE);
        Created later = create(tenant, FakeActionHandlers.TYPE);
        admin.sql("update northstar.actions set next_attempt_at = now() + interval '2 hours' where id = :id")
                .param("id", later.id())
                .update();
        Created duringAnAttempt = create(tenant, FakeActionHandlers.TYPE);
        provider.script(duringAnAttempt.key(), new FakeCall.Fail((ActionOutcome.Failed) ActionOutcome.blocked(CODE)));
        run(tenant, duringAnAttempt.id());
        String laterTime = nextAttemptAt(later.id());
        system(tenant, () -> dependents.block(of(due, later), CODE));
        admin.sql("update northstar.actions set next_attempt_at = now() - interval '1 hour' where id = :id")
                .param("id", due.id())
                .update();
        int jobsBefore = jobsFor(due.id());

        assertThat(system(tenant, () -> dependents.unblock(of(due, later, duringAnAttempt), CODE)))
                .isEqualTo(3);

        assertThat(state(due.id())).isEqualTo("pending|0|blocked|template_paused");
        assertThat(secondsUntilNextAttempt(due.id()))
                .as("a time already past resumes it now")
                .isCloseTo(0, within(5.0));
        assertThat(nextAttemptAt(later.id())).as("a time still ahead is kept").isEqualTo(laterTime);
        assertThat(secondsUntilNextAttempt(duringAnAttempt.id())).isCloseTo(0, within(5.0));
        assertThat(jobsFor(due.id())).as("queued for its attempt").isGreaterThan(jobsBefore);
        assertThat(jobsFor(duringAnAttempt.id())).isGreaterThan(1);

        run(tenant, due.id());
        run(tenant, duringAnAttempt.id());
        run(tenant, later.id());
        assertThat(state(due.id())).startsWith("succeeded|1|");
        assertThat(state(duringAnAttempt.id()))
                .as("the blocked attempt was given back")
                .startsWith("succeeded|1|");
        assertThat(state(later.id())).as("not due yet").startsWith("pending|0|");
    }

    @Test
    void W34_failingFailsPendingNeedsReviewAndPausedOnesVisibly() {
        UUID tenant = data.tenant();
        Created pending = create(tenant, FakeActionHandlers.TYPE);
        Created review = createForReview(tenant);
        Created authPaused = create(tenant, FakeActionHandlers.TYPE);
        provider.script(authPaused.key(), FakeCall.authExpired());
        run(tenant, authPaused.id());
        Created blocked = create(tenant, FakeActionHandlers.TYPE);
        system(tenant, () -> dependents.block(of(blocked), CODE));
        Created succeeded = create(tenant, FakeActionHandlers.TYPE);
        run(tenant, succeeded.id());

        int failed = system(
                tenant,
                () -> dependents.fail(of(pending, review, authPaused, blocked, succeeded), "template_rejected"));

        assertThat(failed).isEqualTo(4);
        for (Created each : List.of(pending, review, authPaused, blocked)) {
            assertThat(state(each.id())).isEqualTo("failed|0|permanent|template_rejected");
            assertThat(nextAttemptAtIsNull(each.id())).isTrue();
            assertThat(closed.of(each.id()))
                    .as("its handler hears of it in the transaction that fails it")
                    .containsExactly("failed|template_rejected|true");
            assertThat(alerts.of(each.id()))
                    .as("alerted once, as any final failure")
                    .contains("failed|" + each.id() + "|0|permanent|template_rejected");
        }
        assertThat(state(succeeded.id())).startsWith("succeeded|1|");
        assertThat(SystemScope.call(tenant, actions::failed))
                .as("on the Failed Actions list")
                .extracting(ActionView::id)
                .contains(pending.id(), review.id(), authPaused.id(), blocked.id());
    }

    @Test
    void aFailureRolledBackChangesNothingAndAlertsNobody() {
        UUID tenant = data.tenant();
        Created pending = create(tenant, FakeActionHandlers.TYPE);
        closed.failFor(pending.id());
        try {
            assertThatThrownBy(() -> system(tenant, () -> dependents.fail(of(pending), "template_rejected")))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            closed.stopFailingFor(pending.id());
        }

        assertThat(state(pending.id())).isEqualTo("pending|0||");
        assertThat(alerts.of(pending.id())).isEmpty();
    }

    // P1, P9, P10

    @Test
    void P1_callingAgainChangesNothingMore() {
        UUID tenant = data.tenant();
        Created first = create(tenant, FakeActionHandlers.TYPE);
        Created second = create(tenant, FakeActionHandlers.TYPE);

        assertThat(system(tenant, () -> dependents.block(of(first, second), CODE)))
                .isEqualTo(2);
        assertThat(system(tenant, () -> dependents.block(of(first, second), CODE)))
                .isZero();
        assertThat(system(tenant, () -> dependents.unblock(of(first), CODE))).isOne();
        assertThat(system(tenant, () -> dependents.unblock(of(first), CODE))).isZero();
        assertThat(system(tenant, () -> dependents.fail(of(first, second), "template_deleted")))
                .isEqualTo(2);
        assertThat(system(tenant, () -> dependents.fail(of(first, second), "template_deleted")))
                .isZero();

        assertThat(closed.of(second.id())).containsExactly("failed|template_deleted|true");
        assertThat(system(
                        tenant, () -> dependents.block(new Dependents(FakeActionHandlers.TYPE, FIELD, Set.of()), CODE)))
                .as("no values, no actions")
                .isZero();
    }

    @Test
    void P9_anotherTenantsActionsAreNeverFound() {
        UUID mine = data.tenant();
        UUID theirs = data.tenant();
        Created theirAction = create(theirs, FakeActionHandlers.TYPE);
        Dependents theirSends = of(theirAction);

        assertThat(system(mine, () -> dependents.block(theirSends, CODE))).isZero();
        assertThat(system(mine, () -> dependents.fail(theirSends, "template_rejected")))
                .isZero();
        assertThat(state(theirAction.id())).isEqualTo("pending|0||");
        assertThat(closed.of(theirAction.id())).isEmpty();
    }

    @Test
    void P10_dependentActionsRunOnlyAsTheSystemActorInsideTheCallersTransaction() {
        UUID tenant = data.tenant();
        Created action = create(tenant, FakeActionHandlers.TYPE);
        Dependents named = of(action);

        assertThatThrownBy(() -> SystemScope.call(tenant, () -> dependents.block(named, CODE)))
                .as("no transaction of the caller's to join")
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.execute(status -> dependents.fail(named, "template_rejected")))
                .as("no tenant context")
                .isInstanceOf(MissingTenantContextException.class);
        TestData.Member member = data.member("admin");
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                        () -> transactions.execute(status -> dependents.fail(named, "template_rejected"))))
                .as("a user, even an admin")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");
        assertThatThrownBy(() -> system(tenant, () -> dependents.block(named, "Not a code")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Dependents(FakeActionHandlers.TYPE, "payload') or true --", Set.of("x")))
                .as("the field is a plain name")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state(action.id())).isEqualTo("pending|0||");
    }

    @Test
    void theDatabaseTakesBlockedAsAFailureClassAndNothingElseNew() {
        UUID tenant = data.tenant();
        Created action = create(tenant, FakeActionHandlers.TYPE);

        assertThat(admin.sql("""
                                update northstar.actions set status = 'paused', last_error_class = 'blocked',
                                  last_error_code = 'template_paused' where id = :id""").param("id", action.id()).update()).isOne();
        assertThatThrownBy(() -> admin.sql(
                                "update northstar.actions set last_error_class = 'paused_by_meta' where id = :id")
                        .param("id", action.id())
                        .update())
                .rootCause()
                .hasMessageContaining("actions_last_error_class_check");
    }

    // Helpers

    /** An action and its idempotency key (the fake provider's script key). */
    private record Created(UUID id, String key, UUID messageId) {}

    private Created create(UUID tenant, String type) {
        return create(tenant, type, false);
    }

    private Created createForReview(UUID tenant) {
        return create(tenant, FakeActionHandlers.TYPE, true);
    }

    private Created create(UUID tenant, String type, boolean needsReview) {
        String key = "test:" + UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ActionView view = SystemScope.call(
                        tenant,
                        () -> actions.create(new NewAction(
                                type,
                                key,
                                needsReview ? ActionMode.APPROVAL : ActionMode.AUTOMATIC,
                                needsReview,
                                "{\"" + FIELD + "\": \"" + messageId + "\"}",
                                null,
                                null,
                                null)))
                .action();
        return new Created(view.id(), key, messageId);
    }

    private static Set<String> values(Created... created) {
        return java.util.Arrays.stream(created)
                .map(each -> each.messageId().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Dependents of(Created... created) {
        return new Dependents(FakeActionHandlers.TYPE, FIELD, values(created));
    }

    /** As the system actor, inside one transaction, as a caller of DependentActions runs it. */
    private <T> T system(UUID tenant, Supplier<T> work) {
        return SystemScope.call(tenant, () -> transactions.execute(status -> work.get()));
    }

    private void run(UUID tenant, UUID action) {
        executeAction.run(new ExecuteAction(tenant, action));
    }

    /** status|attempts|last_error_class|last_error_code */
    private String state(UUID id) {
        return admin.sql("""
                        select status || '|' || attempts || '|' || coalesce(last_error_class, '') || '|'
                               || coalesce(last_error_code, '')
                        from northstar.actions where id = :id""").param("id", id).query(String.class).single();
    }

    private String nextAttemptAt(UUID id) {
        return admin.sql("select coalesce(next_attempt_at::text, '') from northstar.actions where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private boolean nextAttemptAtIsNull(UUID id) {
        return nextAttemptAt(id).isEmpty();
    }

    private double secondsUntilNextAttempt(UUID id) {
        return admin.sql(
                        "select extract(epoch from next_attempt_at - now())::float8 from northstar.actions where id = :id")
                .param("id", id)
                .query(Double.class)
                .single();
    }

    private void makeDue(UUID id) {
        admin.sql("update northstar.actions set next_attempt_at = now() - interval '1 second' where id = :id")
                .param("id", id)
                .update();
    }

    private int jobsFor(UUID actionId) {
        return admin.sql("select count(*) from jobrunr.jobrunr_jobs where jobasjson like :id")
                .param("id", "%" + actionId + "%")
                .query(Integer.class)
                .single();
    }
}
