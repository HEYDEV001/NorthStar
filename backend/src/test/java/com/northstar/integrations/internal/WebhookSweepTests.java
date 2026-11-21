package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.jobs.RecurringPlatformJob;
import com.northstar.jobs.SystemScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The webhook sweep (step 2.4a, integration-specs.md section 1) against real Postgres with RLS: rows still unprocessed
 * past the grace period are queued again under one job per retry step, a failed row is closed after 24 hours keeping its
 * code, a row never attempted is never given up on, and each tenant's sweep reads and queues only that tenant's rows.
 *
 * <p>The worker is off here, so queued jobs stay in JobRunr's table to be looked up by their IDs. Another cached test
 * context may run a worker, and its sweep, meanwhile; it follows the same schedule and creates the same IDs, so every
 * check is by job ID or by what a tenant's scope can reach, never a count of jobs or an absence another sweep could
 * break.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class WebhookSweepTests {

    private final UnprocessedWebhookSweeper platformSweeper;
    private final TenantWebhookSweeper tenantSweeper;
    private final List<RecurringPlatformJob> recurringJobs;
    private final UnprocessedWebhookEvents events;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    WebhookSweepTests(
            @Autowired UnprocessedWebhookSweeper platformSweeper,
            @Autowired TenantWebhookSweeper tenantSweeper,
            @Autowired List<RecurringPlatformJob> recurringJobs,
            @Autowired UnprocessedWebhookEvents events,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.platformSweeper = platformSweeper;
        this.tenantSweeper = tenantSweeper;
        this.recurringJobs = recurringJobs;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void theSweepRunsEveryMinuteOnTheWorker() {
        assertThat(recurringJobs)
                .filteredOn(job -> job.id().equals("sweep-unprocessed-webhooks"))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.interval()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(job.request()).isInstanceOf(SweepUnprocessedWebhooks.class);
                });
    }

    @Test
    void aRowWhoseJobWasLostIsQueuedAgain() {
        UUID tenant = data.tenant();
        // A committed row whose job was never created (a crash between the commit and the enqueue).
        UUID row = messageRow(tenant, "3 minutes", null);

        long before = interval();
        platformSweeper.run(new SweepUnprocessedWebhooks());
        long after = interval();
        assertThat(jobExists(UnprocessedWebhookSweeper.sweepJobIdFor(tenant, before))
                        || jobExists(UnprocessedWebhookSweeper.sweepJobIdFor(tenant, after)))
                .as("one sweep queued for the tenant")
                .isTrue();

        sweep(tenant);

        UUID retry = ProcessWhatsAppWebhook.retryJobIdFor(row, 1);
        assertThat(jobExists(retry)).isTrue();
        assertThat(jobJson(retry))
                .as("the job carries IDs only: its tenant and its row")
                .contains(tenant.toString(), row.toString(), ProcessWhatsAppWebhook.class.getName());
        assertThat(processed(row)).isFalse();
    }

    @Test
    void rowsInsideTheGracePeriodAndProcessedRowsAreLeftAloneAndStatusRowsAreSwept() {
        UUID tenant = data.tenant();
        UUID fresh = messageRow(tenant, "10 seconds", null);
        UUID processed = messageRow(tenant, "3 minutes", null);
        admin.sql("update northstar.webhook_events set processed_at = now() where id = :id")
                .param("id", processed)
                .update();
        UUID status = admin.sql("""
                        insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload, received_at)
                        values (:t, 'whatsapp', :key, '{}', now() - interval '3 minutes') returning id""")
                .param("t", tenant)
                .param("key", "status:" + WhatsAppPayloads.wamid() + ":delivered")
                .query(UUID.class)
                .single();

        sweep(tenant);

        for (UUID row : List.of(fresh, processed)) {
            assertThat(anyRetryJob(row)).as("row " + row).isFalse();
        }
        assertThat(retryJobs(status)).as("status rows are swept since step 2.7").containsExactly(1);
    }

    @Test
    void eachStepQueuesOneJobHoweverOftenTheSweepRuns() {
        UUID tenant = data.tenant();
        UUID row = messageRow(tenant, "3 minutes", "database_unavailable");

        sweep(tenant);
        sweep(tenant);
        assertThat(retryJobs(row)).containsExactly(1);

        age(row, "11 minutes");
        sweep(tenant);
        sweep(tenant);
        assertThat(retryJobs(row)).containsExactly(1, 2);

        age(row, "30 minutes");
        sweep(tenant);
        assertThat(retryJobs(row)).as("between steps nothing is queued").containsExactly(1, 2);

        age(row, "61 minutes");
        sweep(tenant);
        assertThat(retryJobs(row)).containsExactly(1, 2, 3);
        assertThat(processed(row)).isFalse();
    }

    @Test
    void aFailedRowIsClosedAfter24HoursKeepingItsCode(CapturedOutput output) {
        UUID tenant = data.tenant();
        String wamid = WhatsAppPayloads.wamid();
        String marker = WhatsAppPayloads.marker();
        UUID row = messageRow(tenant, "25 hours", "database_unavailable", wamid, marker);
        UUID young = messageRow(tenant, "23 hours", "database_unavailable");

        sweep(tenant);

        assertThat(processed(row)).as("closed: never retried again").isTrue();
        assertThat(error(row)).as("the last failure is kept").isEqualTo("database_unavailable");
        assertThat(anyRetryJob(row)).isFalse();
        assertThat(processed(young)).as("inside the retry window").isFalse();
        assertThat(output.getAll())
                .contains("webhook.failed provider=whatsapp webhook_event_id=" + row
                        + " error=database_unavailable final=true")
                .doesNotContain(wamid)
                .doesNotContain(marker);

        sweep(tenant);
        assertThat(anyRetryJob(row)).as("a closed row is not looked at again").isFalse();
    }

    @Test
    void aRowNeverAttemptedIsRetriedWhateverItsAgeAndNeverClosed() {
        UUID tenant = data.tenant();
        // Stored long before processing existed, or its every job lost.
        UUID row = messageRow(tenant, "3 days", null);

        sweep(tenant);
        sweep(tenant);

        assertThat(retryJobs(row)).containsExactly(WebhookRetrySchedule.STEPS.size());
        assertThat(processed(row)).isFalse();
        assertThat(error(row)).isNull();
    }

    @Test
    void P9_eachTenantsSweepReadsAndQueuesOnlyThatTenantsRows() {
        UUID a = data.tenant();
        UUID b = data.tenant();
        UUID rowOfA = messageRow(a, "3 minutes", null);
        UUID rowOfB = messageRow(b, "3 minutes", null);
        UUID failedOfB = messageRow(b, "2 days", "database_unavailable");

        // Inside tenant A's scope, B's rows can be neither read nor closed (RLS, and the explicit tenant filter).
        List<UUID> seenByA = asSystem(
                a,
                () -> events.waiting(WebhookRetrySchedule.GRACE, TenantWebhookSweeper.BATCH).stream()
                        .map(UnprocessedWebhookEvents.Waiting::id)
                        .toList());
        assertThat(seenByA).contains(rowOfA).doesNotContain(rowOfB, failedOfB);
        Optional<String> closedByA =
                asSystem(a, () -> events.closeFailed(failedOfB, WebhookRetrySchedule.RETRY_WINDOW));
        assertThat(closedByA).isEmpty();

        long before = interval();
        platformSweeper.run(new SweepUnprocessedWebhooks());
        long after = interval();
        for (UUID tenant : List.of(a, b)) {
            UUID first = UnprocessedWebhookSweeper.sweepJobIdFor(tenant, before);
            UUID second = UnprocessedWebhookSweeper.sweepJobIdFor(tenant, after);
            UUID queued = jobExists(first) ? first : second;
            assertThat(jobExists(queued)).as("a sweep for tenant " + tenant).isTrue();
            assertThat(jobJson(queued))
                    .contains(tenant.toString())
                    .doesNotContain((tenant.equals(a) ? b : a).toString());
        }

        sweep(a);
        assertThat(jobJson(ProcessWhatsAppWebhook.retryJobIdFor(rowOfA, 1)))
                .contains(a.toString())
                .doesNotContain(b.toString());

        sweep(b);
        assertThat(jobJson(ProcessWhatsAppWebhook.retryJobIdFor(rowOfB, 1)))
                .contains(b.toString())
                .doesNotContain(a.toString());
        assertThat(processed(failedOfB)).as("closed by its own tenant's sweep").isTrue();
    }

    @Test
    void theSweepLogsCountsIdsAndCodesOnly(CapturedOutput output) {
        UUID tenant = data.tenant();
        String wamid = WhatsAppPayloads.wamid();
        String marker = WhatsAppPayloads.marker();
        messageRow(tenant, "3 minutes", null, wamid, marker);
        messageRow(tenant, "2 days", "database_conflict", wamid + "x", marker);

        sweep(tenant);

        // Counts only. Not their values: a worker in another cached test context may sweep this tenant first.
        assertThat(output.getAll())
                .containsPattern("webhook\\.swept provider=whatsapp waiting=\\d+ retried=\\d+ closed=\\d+")
                .doesNotContain(wamid)
                .doesNotContain(marker)
                .doesNotContain("910000000001");
    }

    // Helpers

    /** A WhatsApp message row received that long ago (a Postgres interval), with that error (null: none recorded). */
    private UUID messageRow(UUID tenant, String age, String error) {
        return messageRow(tenant, age, error, WhatsAppPayloads.wamid(), "Hi");
    }

    private UUID messageRow(UUID tenant, String age, String error, String wamid, String text) {
        String payload = WhatsAppPayloads.delivery()
                .message("pn-" + UUID.randomUUID(), wamid, text)
                .json();
        return admin.sql("""
                        insert into northstar.webhook_events
                          (tenant_id, provider, provider_event_id, payload, received_at, error)
                        values (:t, 'whatsapp', :key, cast(:payload as jsonb), now() - cast(:age as interval), :error)
                        returning id""")
                .param("t", tenant)
                .param("key", "message:" + wamid)
                .param("payload", payload)
                .param("age", age)
                .param("error", error)
                .query(UUID.class)
                .single();
    }

    private void age(UUID row, String age) {
        admin.sql("update northstar.webhook_events set received_at = now() - cast(:age as interval) where id = :id")
                .param("age", age)
                .param("id", row)
                .update();
    }

    /** Runs the tenant's sweep as the worker does: off any request thread, as the system actor in that tenant. */
    private void sweep(UUID tenant) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            tenantSweeper.run(new SweepTenantWebhooks(tenant));
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    private <T> T asSystem(UUID tenant, Supplier<T> work) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            return SystemScope.call(tenant, () -> transactions.execute(status -> work.get()));
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    private static long interval() {
        return Instant.now().getEpochSecond() / UnprocessedWebhookSweeper.INTERVAL.toSeconds();
    }

    /** The retry steps queued for this row, in order. */
    private List<Integer> retryJobs(UUID row) {
        return IntStream.rangeClosed(1, WebhookRetrySchedule.STEPS.size())
                .filter(step -> jobExists(ProcessWhatsAppWebhook.retryJobIdFor(row, step)))
                .boxed()
                .toList();
    }

    private boolean anyRetryJob(UUID row) {
        return !retryJobs(row).isEmpty();
    }

    private boolean jobExists(UUID jobId) {
        return admin.sql("select count(*) from jobrunr.jobrunr_jobs where id = :id")
                        .param("id", jobId.toString())
                        .query(Long.class)
                        .single()
                > 0;
    }

    private String jobJson(UUID jobId) {
        return admin.sql("select jobasjson from jobrunr.jobrunr_jobs where id = :id")
                .param("id", jobId.toString())
                .query(String.class)
                .single();
    }

    private boolean processed(UUID row) {
        return admin.sql("select processed_at is not null from northstar.webhook_events where id = :id")
                .param("id", row)
                .query(Boolean.class)
                .single();
    }

    private String error(UUID row) {
        return (String) admin.sql("select error from northstar.webhook_events where id = :id")
                .param("id", row)
                .query()
                .singleRow()
                .get("error");
    }
}
