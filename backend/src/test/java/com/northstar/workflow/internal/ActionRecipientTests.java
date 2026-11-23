package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.NotFoundException;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.FakeProvider;
import com.northstar.fakes.Gate;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import com.northstar.workflow.OutboundCancellation;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Actions that message a contact (step 2.5, W32; workflow-spec.md section 13 Opt-out) against real Postgres with RLS:
 * each names its recipient, one for a contact who has opted out is refused at creation (created cancelled) and at
 * execution (cancelled at claim, its handler never called), and an action being created or claimed while an opt-out is
 * being recorded waits for it, then sees it, and never deadlocks with it. Attempts run through
 * {@link ExecuteActionHandler}, exactly as the worker runs them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ActionRecipientTests {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final Actions actions;
    private final OutboundCancellation cancellation;
    private final ExecuteActionHandler executeAction;
    private final FakeProvider provider;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;

    ActionRecipientTests(
            @Autowired Actions actions,
            @Autowired OutboundCancellation cancellation,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeProvider provider,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.actions = actions;
        this.cancellation = cancellation;
        this.executeAction = executeAction;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** Jobs run on worker threads with no web request; Spring's test support binds one to the test thread. */
    @BeforeEach
    void runLikeAWorkerThread() {
        RequestContextHolder.resetRequestAttributes();
    }

    // Refused at creation

    @Test
    void W32_aNewActionForAnOptedOutContactIsCreatedCancelledAndNeverRuns(CapturedOutput output) {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        optOut(contact);
        String key = key();

        Actions.Created created = as(member, () -> actions.create(contactAction(key, contact, false)));

        assertThat(created.created()).isTrue();
        ActionView action = created.action();
        assertThat(action.status()).isEqualTo("cancelled");
        assertThat(action.cancelReason()).isEqualTo("contact_opted_out");
        assertThat(action.recipientContactId()).isEqualTo(contact);
        assertThat(action.nextAttemptAt()).isNull();
        assertThat(jobsFor(action.id())).as("never queued").isZero();
        assertThat(output.getAll())
                .contains("action.cancelled action_id=" + action.id()
                        + " type=test.fake.contact reason=contact_opted_out at=create");

        Actions.Created again = as(member, () -> actions.create(contactAction(key, contact, false)));
        assertThat(again.created()).isFalse();
        assertThat(again.action().id()).isEqualTo(action.id());

        Actions.Created forReview = as(member, () -> actions.create(contactAction(key(), contact, true)));
        assertThat(forReview.action().status()).as("approval mode too").isEqualTo("cancelled");

        run(member.tenantId(), action.id());
        assertThat(provider.calls(key)).isZero();
        assertThat(state(action.id())).isEqualTo("cancelled|contact_opted_out|0");
    }

    @Test
    void aContactTypeNeedsItsRecipientAndOtherTypesTakeNone() {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String withoutRecipient = key();
        String withRecipient = key();

        assertThatThrownBy(() -> as(
                        member,
                        () -> actions.create(new NewAction(
                                FakeActionHandlers.CONTACT_TYPE,
                                withoutRecipient,
                                ActionMode.AUTOMATIC,
                                false,
                                null,
                                null,
                                null,
                                null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs its recipient");
        assertThatThrownBy(() -> as(
                        member,
                        () -> actions.create(new NewAction(
                                FakeActionHandlers.TYPE,
                                withRecipient,
                                ActionMode.AUTOMATIC,
                                false,
                                null,
                                null,
                                null,
                                contact))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes no recipient");
        assertThat(admin.sql("select count(*) from northstar.actions where idempotency_key in (:a, :b)")
                        .param("a", withoutRecipient)
                        .param("b", withRecipient)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void P9_anActionForAnotherTenantsContactIsRefusedAsNotFound() {
        TestData.Member member = data.member("admin");
        UUID theirContact = data.contact(data.tenant());
        String key = key();

        assertThatThrownBy(() -> as(member, () -> actions.create(contactAction(key, theirContact, false))))
                .isInstanceOf(NotFoundException.class);
        assertThat(admin.sql("select count(*) from northstar.actions where idempotency_key = :k")
                        .param("k", key)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aStaffMemberCannotCreateAnActionForAContactTheyCannotSee() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID unassigned = data.contact(owner.tenantId());
        String key = key();

        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(owner.tenantId(), staffUser, Role.STAFF),
                        () -> actions.create(contactAction(key, unassigned, false))))
                .as("fails closed: a contact out of sight is never treated as contactable")
                .isInstanceOf(NotFoundException.class);
    }

    // Refused at execution

    @Test
    void W32_aDueActionWhoseRecipientOptedOutIsCancelledAtClaimAndNeverCalled(CapturedOutput output) {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String key = key();
        UUID id = as(member, () -> actions.create(contactAction(key, contact, false)))
                .action()
                .id();
        // Opted out by a path that did not cancel it, so only the claim stands between it and the provider.
        optOut(contact);

        run(member.tenantId(), id);

        assertThat(provider.calls(key)).isZero();
        assertThat(state(id)).isEqualTo("cancelled|contact_opted_out|0");
        assertThat(output.getAll())
                .contains("action.cancelled action_id=" + id + " type=test.fake.contact reason=contact_opted_out"
                        + " at=claim");
        run(member.tenantId(), id); // and the job running again does nothing
        assertThat(provider.calls(key)).isZero();
    }

    @Test
    void W32_aProcessingAttemptIsNotCancelledButItsRetryIsCancelledAtClaim() throws Exception {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String key = key();
        UUID id = as(member, () -> actions.create(contactAction(key, contact, false)))
                .action()
                .id();
        Gate gate = new Gate();
        provider.script(key, new FakeCall.Gated(gate, FakeCall.serverError(503)));

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<?> attempt = worker.submit(() -> run(member.tenantId(), id));
            assertThat(gate.awaitArrival(WAIT)).isTrue();
            assertThat(state(id)).isEqualTo("processing||1");

            optOut(contact);
            int cancelled = SystemScope.call(
                    member.tenantId(),
                    () -> transactions.execute(status -> cancellation.cancelForOptedOutContact(contact)));
            assertThat(cancelled).as("an attempt in flight cannot be recalled").isZero();
            assertThat(state(id)).isEqualTo("processing||1");

            gate.release();
            attempt.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }
        assertThat(state(id)).as("its retry is scheduled").isEqualTo("pending||1");

        makeDue(id);
        run(member.tenantId(), id);

        assertThat(state(id)).isEqualTo("cancelled|contact_opted_out|1");
        assertThat(provider.calls(key)).as("only the attempt already in flight").isOne();
    }

    @Test
    void W32_aLostAttemptIsNotTakenOverOnceItsRecipientOptedOut() {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String key = key();
        UUID id = as(member, () -> actions.create(contactAction(key, contact, false)))
                .action()
                .id();
        admin.sql("""
                        update northstar.actions set status = 'processing', attempts = 1, next_attempt_at = null,
                          last_attempt_at = now() - interval '10 minutes', lease_expires_at = now() - interval '5 minutes'
                        where id = :id""").param("id", id).update();
        optOut(contact);

        run(member.tenantId(), id);

        assertThat(state(id)).isEqualTo("cancelled|contact_opted_out|1");
        assertThat(provider.calls(key)).isZero();
    }

    @Test
    void W32_approvingOrResumingACancelledActionChangesNothing() {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String reviewKey = key();
        String pausedKey = key();
        UUID review = as(member, () -> actions.create(contactAction(reviewKey, contact, true)))
                .action()
                .id();
        UUID paused = as(member, () -> actions.create(contactAction(pausedKey, contact, false)))
                .action()
                .id();
        admin.sql("""
                        update northstar.actions set status = 'paused', next_attempt_at = null,
                          last_error_class = 'auth', last_error_code = 'token_expired'
                        where id = :id""").param("id", paused).update();
        optOut(contact);
        int cancelled = SystemScope.call(
                member.tenantId(),
                () -> transactions.execute(status -> cancellation.cancelForOptedOutContact(contact)));
        assertThat(cancelled).isEqualTo(2);

        assertThat(as(member, () -> actions.approve(review)).status()).isEqualTo("cancelled");
        assertThat(as(member, () -> actions.resumePaused(FakeActionHandlers.CONTACT_TYPE)))
                .isZero();
        run(member.tenantId(), review);
        run(member.tenantId(), paused);

        assertThat(state(review)).isEqualTo("cancelled|contact_opted_out|0");
        assertThat(state(paused)).isEqualTo("cancelled|contact_opted_out|0");
        assertThat(provider.calls(reviewKey) + provider.calls(pausedKey)).isZero();
        assertThat(admin.sql("select count(*) from northstar.audit_log where target_id = :id")
                        .param("id", review)
                        .query(Long.class)
                        .single())
                .as("no approval was recorded")
                .isZero();
    }

    // An opt-out in progress

    @Test
    void W32_aClaimDuringAnOptOutWaitsForItThenCancelsAndNeverDeadlocks() throws Exception {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String key = key();
        UUID id = as(member, () -> actions.create(contactAction(key, contact, false)))
                .action()
                .id();

        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            optOutInProgress(contact, () -> {
                        Future<?> claim = worker.submit(() -> run(member.tenantId(), id));
                        awaitWaitingForTheContact();
                        // The inbound job's next step: cancel the contact's actions. The claim must not hold the
                        // action's lock
                        // while it waits for the contact's, or this would wait for it in turn (a deadlock);
                        // lock_timeout makes
                        // that a failure here rather than a hang.
                        cancelAsTheInboundJobDoes(id);
                        return claim;
                    })
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(state(id)).isEqualTo("cancelled|contact_opted_out|0");
        assertThat(provider.calls(key)).isZero();
    }

    @Test
    void W32_anActionCreatedDuringAnOptOutWaitsForItAndIsCreatedCancelled() throws Exception {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String key = key();

        ActionView created;
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<Actions.Created> creating = optOutInProgress(contact, () -> {
                Future<Actions.Created> create =
                        worker.submit(() -> as(member, () -> actions.create(contactAction(key, contact, false))));
                awaitWaitingForTheContact();
                return create;
            });
            created = creating.get(WAIT.toSeconds(), TimeUnit.SECONDS).action();
        }

        assertThat(created.status()).isEqualTo("cancelled");
        assertThat(created.cancelReason()).isEqualTo("contact_opted_out");
        assertThat(jobsFor(created.id())).isZero();
    }

    // Helpers

    private static NewAction contactAction(String key, UUID contact, boolean needsReview) {
        return new NewAction(
                FakeActionHandlers.CONTACT_TYPE,
                key,
                needsReview ? ActionMode.APPROVAL : ActionMode.AUTOMATIC,
                needsReview,
                "{\"message\": \"m-1\"}",
                null,
                null,
                contact);
    }

    private static <T> T as(TestData.Member member, Supplier<T> work) {
        return TenantScope.call(new TenantContext(member.tenantId(), member.userId(), Role.ADMIN), work);
    }

    private void run(UUID tenantId, UUID actionId) {
        executeAction.run(new ExecuteAction(tenantId, actionId));
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }

    /** Opts the contact out as an operator would, without cancelling anything. */
    private void optOut(UUID contact) {
        admin.sql(
                        "update northstar.contacts set consent_state = 'opted_out', opt_out_at = now(), last_opt_out_at = now() where id = :id")
                .param("id", contact)
                .update();
    }

    /**
     * Runs the work while another transaction holds the contact's update lock and has opted it out without committing,
     * as the inbound job does while it records an opt-out; commits once the work returns, and returns its result.
     */
    private <T> T optOutInProgress(UUID contact, Supplier<T> work) {
        JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
        return new TransactionTemplate(new DataSourceTransactionManager(postgres.dataSource())).execute(status -> {
            jdbc.sql("set local lock_timeout = '3s'").update();
            jdbc.sql("select id from northstar.contacts where id = :id for update")
                    .param("id", contact)
                    .query(UUID.class)
                    .single();
            jdbc.sql(
                            "update northstar.contacts set consent_state = 'opted_out', opt_out_at = now(), last_opt_out_at = now() where id = :id")
                    .param("id", contact)
                    .update();
            return work.get();
        });
    }

    /** In the opt-out's transaction: the inbound job's cancellation of the contact's actions. */
    private void cancelAsTheInboundJobDoes(UUID action) {
        JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
        assertThat(jdbc.sql("""
                                update northstar.actions
                                set status = 'cancelled', cancel_reason = 'contact_opted_out', next_attempt_at = null
                                where id = :id and status in ('pending', 'needs_review', 'paused')""").param("id", action).update()).isOne();
    }

    /** Until another session is waiting for a lock on the shared read of a contact's consent. */
    private void awaitWaitingForTheContact() {
        await().atMost(WAIT).until(() -> admin.sql("""
                                        select count(*) from pg_stat_activity
                                        where wait_event_type = 'Lock' and query like '%northstar.contacts%for share%'""").query(Long.class).single() == 1);
    }

    /** status|cancel_reason|attempts */
    private String state(UUID id) {
        return admin.sql("""
                        select status || '|' || coalesce(cancel_reason, '') || '|' || attempts
                        from northstar.actions where id = :id""").param("id", id).query(String.class).single();
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
