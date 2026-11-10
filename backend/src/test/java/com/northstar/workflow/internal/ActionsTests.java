package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.jobs.JobTenantException;
import com.northstar.jobs.Jobs;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The action core (step 1.10, workflow-spec.md sections 9 and 10) against real Postgres with RLS. Attempts run through
 * {@link ExecuteActionHandler}, exactly as the worker runs them, without waiting for JobRunr to poll.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ActionsTests {

    /** The standard backoff's waits, in seconds: 30 s, 1 m, 5 m, 15 m, 1 h. */
    private static final long[] WAITS = {30, 60, 300, 900, 3600};

    private final Actions actions;
    private final ExecuteActionHandler executeAction;
    private final TenantActionSweeper tenantSweeper;
    private final ScriptedActionHandler provider;
    private final SlowActionHandler slow;
    private final RecordingActionAlerts alerts;
    private final Jobs jobs;
    private final TestData data;
    private final JdbcClient admin;

    ActionsTests(
            @Autowired Actions actions,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired TenantActionSweeper tenantSweeper,
            @Autowired ScriptedActionHandler provider,
            @Autowired SlowActionHandler slow,
            @Autowired RecordingActionAlerts alerts,
            @Autowired Jobs jobs,
            @Autowired PostgresAdmin postgres) {
        this.actions = actions;
        this.executeAction = executeAction;
        this.tenantSweeper = tenantSweeper;
        this.provider = provider;
        this.slow = slow;
        this.alerts = alerts;
        this.jobs = jobs;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /**
     * Spring's test framework binds a mock web request to the test thread, and TenantScope refuses the system context
     * on any thread serving a request. Jobs run on worker threads with no request, so these tests drop it first.
     */
    @BeforeEach
    void runLikeAWorkerThread() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anActionRunsInItsTenantScopeAndSucceeds() {
        TestData.Member member = data.member("admin");
        String key = key();
        ActionView created = create(member, key, false);
        assertThat(created.status()).isEqualTo("pending");
        assertThat(jobsFor(created.id())).isEqualTo(1);

        run(member.tenantId(), created.id());

        assertThat(state(created.id())).isEqualTo("succeeded|1|||");
        assertThat(provider.calls(key)).isOne();
        assertThat(provider.tenantSeen(key)).isEqualTo(member.tenantId());
        assertThat(admin.sql("select result->>'providerId' from northstar.actions where id = :id")
                        .param("id", created.id())
                        .query(String.class)
                        .single())
                .isEqualTo("fake-1");
    }

    @Test
    void sameActionCreatedTwiceIsOneRowAndOneJob() {
        TestData.Member member = data.member("admin");
        String key = key();
        Actions.Created first = as(member, () -> actions.create(newAction(key, false)));
        Actions.Created second = as(member, () -> actions.create(newAction(key, false)));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.action().id()).isEqualTo(first.action().id());
        assertThat(jobsFor(first.action().id())).isEqualTo(1);
    }

    @Test
    void sameActionExecutedTwiceDoesTheSideEffectOnce() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, key, false).id();

        run(member.tenantId(), id);
        run(member.tenantId(), id); // the job delivered again: the action already succeeded

        assertThat(provider.calls(key)).isOne();
        assertThat(state(id)).isEqualTo("succeeded|1|||");
    }

    @Test
    void concurrentDeliveriesOfTheSameJobDoTheSideEffectOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            TestData.Member member = data.member("admin");
            String key = key();
            UUID id = create(member, key, false).id();
            provider.delay(key, Duration.ofMillis(300)); // holds the lease while the second delivery arrives

            together(() -> run(member.tenantId(), id), () -> run(member.tenantId(), id));

            assertThat(provider.calls(key)).as("round " + round).isOne();
            assertThat(state(id)).isEqualTo("succeeded|1|||");
        }
    }

    @Test
    void anAttemptLostToACrashIsTakenOverAfterItsLease() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, key, false).id();
        // A worker claimed attempt 1 and died before recording it.
        admin.sql("""
                        update northstar.actions set status = 'processing', attempts = 1, next_attempt_at = null,
                               lease_expires_at = now() + interval '5 minutes' where id = :id""").param("id", id).update();
        run(member.tenantId(), id);
        assertThat(provider.calls(key)).as("lease still live").isZero();

        admin.sql("update northstar.actions set lease_expires_at = now() - interval '1 second' where id = :id")
                .param("id", id)
                .update();
        run(member.tenantId(), id);

        assertThat(provider.calls(key)).isOne();
        assertThat(state(id)).as("the lost attempt still counts").isEqualTo("succeeded|2|||");
    }

    @Test
    void retryableFailuresFollowTheBackoffTableThenFailWithAnAlert() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, key, false).id();
        provider.script(
                key,
                ActionOutcome.retryable("http_503"),
                ActionOutcome.retryable("http_503"),
                ActionOutcome.retryable("timeout"),
                ActionOutcome.retryable("http_503"),
                ActionOutcome.retryable("http_503"),
                ActionOutcome.retryable("http_502"));

        for (int attempt = 1; attempt <= 5; attempt++) {
            run(member.tenantId(), id);
            assertThat(state(id)).startsWith("pending|" + attempt + "|retryable|");
            assertThat(secondsUntilNextAttempt(id))
                    .as("wait after attempt " + attempt)
                    .isCloseTo(WAITS[attempt - 1], within(5.0));
            run(member.tenantId(), id); // early: not due yet, nothing happens
            assertThat(provider.calls(key)).isEqualTo(attempt);
            makeDue(id);
        }
        run(member.tenantId(), id);

        assertThat(provider.calls(key)).as("a first try plus 5 retries").isEqualTo(6);
        assertThat(state(id)).isEqualTo("failed|6|retryable|http_502|");
        assertThat(alerts.of(id))
                .containsExactly(
                        "failed_repeatedly|" + id + "|3|retryable|timeout", "failed|" + id + "|6|retryable|http_502");
        assertThat(as(member, actions::failed)).extracting(ActionView::id).contains(id);

        run(member.tenantId(), id); // a failed action never runs again, and never alerts twice
        assertThat(provider.calls(key)).isEqualTo(6);
        assertThat(alerts.of(id)).hasSize(2);
    }

    @Test
    void aLongerRetryAfterDelaysTheNextAttemptButNeverPastTheWindow() {
        TestData.Member member = data.member("admin");
        String later = key();
        UUID delayed = create(member, later, false).id();
        provider.script(later, ActionOutcome.retryableAfter("http_429", Duration.ofMinutes(10)));
        run(member.tenantId(), delayed);
        assertThat(secondsUntilNextAttempt(delayed)).isCloseTo(600, within(5.0));

        String shorter = key();
        UUID scheduled = create(member, shorter, false).id();
        provider.script(shorter, ActionOutcome.retryableAfter("http_429", Duration.ofSeconds(5)));
        run(member.tenantId(), scheduled);
        assertThat(secondsUntilNextAttempt(scheduled))
                .as("never earlier than the schedule")
                .isCloseTo(30, within(5.0));

        String tooLong = key();
        UUID capped = create(member, tooLong, false).id();
        provider.script(tooLong, ActionOutcome.retryableAfter("http_429", Duration.ofHours(5)));
        run(member.tenantId(), capped);
        assertThat(admin.sql("""
                        select extract(epoch from next_attempt_at - created_at)::float8
                        from northstar.actions where id = :id""").param("id", capped).query(Double.class).single())
                .as("capped at the 81.5-minute window")
                .isCloseTo(4890, within(5.0));
    }

    @Test
    void aPermanentFailureFailsAtOnceWithOneAlert() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, key, false).id();
        provider.script(key, ActionOutcome.permanent("invalid_recipient"));

        run(member.tenantId(), id);
        run(member.tenantId(), id);

        assertThat(provider.calls(key)).isOne();
        assertThat(state(id)).isEqualTo("failed|1|permanent|invalid_recipient|");
        assertThat(alerts.of(id)).containsExactly("failed|" + id + "|1|permanent|invalid_recipient");
    }

    @Test
    void anAuthFailurePausesWithoutUsingAnAttemptAndResumes() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, key, false).id();
        provider.script(key, ActionOutcome.auth("token_expired"), ActionOutcome.auth("token_expired"));

        run(member.tenantId(), id);
        assertThat(state(id)).isEqualTo("paused|0|auth|token_expired|");
        assertThat(alerts.of(id)).containsExactly("auth_failed|" + id + "|token_expired");
        run(member.tenantId(), id);
        assertThat(provider.calls(key)).as("paused actions don't run").isOne();

        assertThat(as(member, () -> actions.resumePaused(ScriptedActionHandler.TYPE)))
                .isPositive();
        run(member.tenantId(), id);
        assertThat(state(id)).isEqualTo("paused|0|auth|token_expired|");
        as(member, () -> actions.resumePaused(ScriptedActionHandler.TYPE));
        run(member.tenantId(), id);

        assertThat(provider.calls(key)).isEqualTo(3);
        assertThat(state(id)).as("only the successful attempt counts").isEqualTo("succeeded|1|||");
    }

    @Test
    void aHandlerPastItsTimeoutIsRecordedAsRetryableAndInterrupted() throws Exception {
        TestData.Member member = data.member("admin");
        slow.interrupted = new CountDownLatch(1);
        UUID id = as(
                        member,
                        () -> actions.create(new NewAction(
                                SlowActionHandler.TYPE, key(), ActionMode.AUTOMATIC, false, null, null, null)))
                .action()
                .id();

        long started = System.nanoTime();
        run(member.tenantId(), id);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(state(id)).isEqualTo("pending|1|retryable|timeout|");
        assertThat(admin.sql("select lease_expires_at is null from northstar.actions where id = :id")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .as("recorded, not left processing for a takeover")
                .isTrue();
        assertThat(slow.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void handlerTimeoutsMustBeShorterThanTheLease() {
        assertThatThrownBy(() -> new ActionHandlers(List.of(slow), Duration.ofMillis(300)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than the lease");
        assertThatThrownBy(() -> new ActionHandlers(List.of(provider, provider), Duration.ofMinutes(5)))
                .hasMessageContaining("Two action handlers");
    }

    @Test
    void needsReviewDoesNotRunUntilApproved() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, key, true).id();
        assertThat(state(id)).isEqualTo("needs_review|0|||");
        assertThat(jobsFor(id)).isZero();

        run(member.tenantId(), id);
        tenantSweeperRun(member.tenantId());
        assertThat(provider.calls(key)).isZero();

        ActionView approved = as(member, () -> actions.approve(id));
        assertThat(approved.status()).isEqualTo("pending");
        assertThat(approved.approverMembershipId()).isEqualTo(member.membershipId());
        assertThat(approved.approvedAt()).isNotNull();
        as(member, () -> actions.approve(id)); // approving again changes nothing
        assertThat(audit(member.tenantId(), id))
                .containsExactly(
                        "action.approved|" + member.userId() + "|{\"to\": \"pending\", \"from\": \"needs_review\"}");

        run(member.tenantId(), id);
        assertThat(provider.calls(key)).isOne();
        assertThat(state(id)).isEqualTo("succeeded|1|||");
    }

    @Test
    void theSweeperQueuesDueActionsWhoseJobWasLost() {
        TestData.Member member = data.member("admin");
        UUID id = create(member, key(), false).id();
        admin.sql("delete from jobrunr.jobrunr_jobs where jobasjson like :id")
                .param("id", "%" + id + "%")
                .update();
        assertThat(jobsFor(id)).isZero();

        tenantSweeperRun(member.tenantId());
        tenantSweeperRun(member.tenantId()); // the same due time gives the same job ID: still one job

        assertThat(jobsFor(id)).isOne();
    }

    @Test
    void P10_jobWithoutATenantFailsLoudlyAndRunsNothing(CapturedOutput output) {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, key, false).id();

        assertThatThrownBy(() -> executeAction.run(new ExecuteAction(null, id))).isInstanceOf(JobTenantException.class);
        assertThatThrownBy(() -> executeAction.run(new ExecuteAction(UUID.randomUUID(), id)))
                .isInstanceOf(JobTenantException.class);

        assertThat(output.getAll())
                .contains("ERROR")
                .contains("job.tenant_missing")
                .contains("job.tenant_unknown");
        assertThat(provider.calls(key)).isZero();
        assertThat(state(id)).isEqualTo("pending|0|||");
    }

    @Test
    void P10_jobCannotSeeAnotherTenantsActions(CapturedOutput output) {
        TestData.Member mine = data.member("admin");
        TestData.Member theirs = data.member("admin");
        String key = key();
        UUID theirAction = create(theirs, key, false).id();

        run(mine.tenantId(), theirAction); // a job for my tenant naming their action

        assertThat(provider.calls(key)).isZero();
        assertThat(state(theirAction)).isEqualTo("pending|0|||");
        assertThat(output.getAll()).contains("Not found: action " + theirAction);
        assertThat(as(mine, () -> actions.find(theirAction))).isEmpty();
        assertThat(as(mine, actions::failed)).extracting(ActionView::id).doesNotContain(theirAction);
    }

    @Test
    void P10_aTenantCanOnlyQueueJobsForItself() {
        TestData.Member mine = data.member("admin");
        UUID other = data.tenant();
        assertThatThrownBy(() -> as(mine, () -> {
                    jobs.enqueue(UUID.randomUUID(), new ExecuteAction(other, UUID.randomUUID()));
                    return null;
                }))
                .isInstanceOf(JobTenantException.class);
        assertThatThrownBy(() -> jobs.enqueue(UUID.randomUUID(), new ExecuteAction(null, UUID.randomUUID())))
                .isInstanceOf(JobTenantException.class);
    }

    // Helpers.

    private ActionView create(TestData.Member member, String key, boolean needsReview) {
        return as(member, () -> actions.create(newAction(key, needsReview))).action();
    }

    private static NewAction newAction(String key, boolean needsReview) {
        return new NewAction(
                ScriptedActionHandler.TYPE,
                key,
                needsReview ? ActionMode.APPROVAL : ActionMode.AUTOMATIC,
                needsReview,
                "{\"draft\": \"quote-v1\"}",
                null,
                null);
    }

    private static <T> T as(TestData.Member member, Supplier<T> work) {
        return TenantScope.call(new TenantContext(member.tenantId(), member.userId(), Role.ADMIN), work);
    }

    private void run(UUID tenantId, UUID actionId) {
        executeAction.run(new ExecuteAction(tenantId, actionId));
    }

    private void tenantSweeperRun(UUID tenantId) {
        tenantSweeper.run(new SweepTenantActions(tenantId));
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }

    /** status|attempts|last_error_class|last_error_code| */
    private String state(UUID id) {
        Map<String, Object> row = admin.sql("""
                        select status, attempts, coalesce(last_error_class, '') as error_class,
                               coalesce(last_error_code, '') as error_code
                        from northstar.actions where id = :id""").param("id", id).query().singleRow();
        return row.get("status") + "|" + row.get("attempts") + "|" + row.get("error_class") + "|"
                + row.get("error_code") + "|";
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

    private List<String> audit(UUID tenant, UUID target) {
        return admin.sql("""
                        select action || '|' || coalesce(actor_user_id::text, '') || '|' || coalesce(metadata::text, '')
                        from northstar.audit_log where tenant_id = :t and target_id = :id order by created_at""")
                .param("t", tenant)
                .param("id", target)
                .query(String.class)
                .list();
    }

    private static void together(Runnable... work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(work.length)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : work) {
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
    }
}
