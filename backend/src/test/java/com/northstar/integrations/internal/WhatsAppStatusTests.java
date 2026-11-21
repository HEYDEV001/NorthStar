package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.contacts.DeliveryStatus;
import com.northstar.contacts.OutboundMessages;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineEvents;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Delivery status webhooks (step 2.7, integration-specs.md section 2, How step 2.7 applies statuses) through the real
 * intake (step 2.2), job and sweep (step 2.4a), against real Postgres with RLS. Statuses only move forward, whatever
 * order Meta sends them in; a failure of a sent message sets its code and leaves the send action alone; a status that
 * beats its message waits for the sweep; and each one touches only its webhook row and its message.
 *
 * <p>The worker is off here, so jobs the sweep queues stay in JobRunr's table and are run by the test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppStatusTests {

    private static final Instant DELIVERED_AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-11-21T10:01:00Z");

    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler job;
    private final TenantWebhookSweeper sweeper;
    private final OutboundMessages outbound;
    private final EngineEvents events;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;

    WhatsAppStatusTests(
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler job,
            @Autowired TenantWebhookSweeper sweeper,
            @Autowired OutboundMessages outbound,
            @Autowired EngineEvents events,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.intake = intake;
        this.job = job;
        this.sweeper = sweeper;
        this.outbound = outbound;
        this.events = events;
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

    // Forward only

    @Test
    void statusesMoveTheMessageForwardWithMetasTimes() {
        Sent sent = sent();

        UUID delivered = deliver(sent, "delivered", DELIVERED_AT, null);
        run(sent, delivered);
        UUID read = deliver(sent, "read", READ_AT, null);
        run(sent, read);

        assertThat(message(sent)).isEqualTo("read||" + DELIVERED_AT.getEpochSecond() + "|" + READ_AT.getEpochSecond());
        assertThat(row(delivered)).isEqualTo("true|");
        assertThat(row(read)).isEqualTo("true|");
        assertThat(statusEvents(sent)).containsExactly("delivered|", "read|");
    }

    @Test
    void aStatusMayJumpStraightToRead() {
        Sent sent = sent();

        run(sent, deliver(sent, "read", READ_AT, null));

        assertThat(message(sent))
                .as("delivered_at stays empty: Meta sent no delivered")
                .isEqualTo("read|||" + READ_AT.getEpochSecond());
        assertThat(statusEvents(sent)).containsExactly("read|");
    }

    @Test
    void aLateDeliveredAfterReadKeepsReadAndFillsItsTimeOnce(CapturedOutput output) {
        Sent sent = sent();
        run(sent, deliver(sent, "read", READ_AT, null));

        UUID late = deliver(sent, "delivered", DELIVERED_AT, null);
        run(sent, late);

        assertThat(message(sent)).isEqualTo("read||" + DELIVERED_AT.getEpochSecond() + "|" + READ_AT.getEpochSecond());
        assertThat(row(late)).isEqualTo("true|");
        assertThat(statusEvents(sent)).as("delivered's own event, and no other").containsExactly("read|", "delivered|");
        assertThat(output.getAll())
                .contains("webhook.status_late_delivered provider=whatsapp webhook_event_id=" + late + " message_id="
                        + sent.message() + " delivered_at_filled=true");

        // A replay (processed_at cleared): delivered_at is set once, and the event is already there.
        replay(late);
        run(sent, late);
        assertThat(message(sent)).isEqualTo("read||" + DELIVERED_AT.getEpochSecond() + "|" + READ_AT.getEpochSecond());
        assertThat(statusEvents(sent)).containsExactly("read|", "delivered|");
        assertThat(output.getAll()).contains("delivered_at_filled=false");
    }

    @Test
    void deliveredBeforeSentStaysDeliveredAndTheLateSentIsIgnored(CapturedOutput output) {
        Sent sent = sent();
        run(sent, deliver(sent, "delivered", DELIVERED_AT, null));

        UUID lateSent = deliver(sent, "sent", DELIVERED_AT.minusSeconds(5), null);
        run(sent, lateSent);

        assertThat(message(sent)).isEqualTo("delivered||" + DELIVERED_AT.getEpochSecond() + "|");
        assertThat(row(lateSent)).as("closed as processed").isEqualTo("true|");
        assertThat(statusEvents(sent))
                .as("nothing recorded for an ignored status")
                .containsExactly("delivered|");
        assertThat(output.getAll())
                .contains("webhook.status_ignored provider=whatsapp webhook_event_id=" + lateSent + " message_id="
                        + sent.message() + " from=delivered to=sent");
    }

    @Test
    void metasSentAfterOurOwnChangesNothingAndIsRecorded(CapturedOutput output) {
        Sent sent = sent();

        UUID confirmed = deliver(sent, "sent", DELIVERED_AT, null);
        run(sent, confirmed);

        assertThat(message(sent)).isEqualTo("sent|||");
        assertThat(row(confirmed)).isEqualTo("true|");
        assertThat(statusEvents(sent)).containsExactly("sent|");
        assertThat(output.getAll())
                .contains("webhook.status_unchanged provider=whatsapp webhook_event_id=" + confirmed);
    }

    // Duplicates (P1)

    @Test
    void P1_aDuplicateStatusIsStoredOnceAndAReplayOrConcurrentRunChangesNothing() throws Exception {
        Sent sent = sent();
        UUID delivered = deliver(sent, "delivered", DELIVERED_AT, null);
        assertThat(deliver(sent, "delivered", DELIVERED_AT, null))
                .as("the second copy is not stored")
                .isEqualTo(delivered);

        runTogether(sent.tenant(), List.of(delivered, delivered, delivered));
        replay(delivered);
        run(sent, delivered);

        assertThat(message(sent)).isEqualTo("delivered||" + DELIVERED_AT.getEpochSecond() + "|");
        assertThat(statusEvents(sent)).containsExactly("delivered|");
        assertThat(row(delivered)).isEqualTo("true|");
    }

    // Failures

    @Test
    void aFailureReplacesSentWithTheMappedCodeAndTheSendStaysSucceeded(CapturedOutput output) {
        Sent mapped = sent();
        Sent unlisted = sent();
        Sent noCode = sent();
        UUID action = succeededSend(mapped);

        UUID failed = deliver(mapped, "failed", DELIVERED_AT, 131026);
        run(mapped, failed);
        run(unlisted, deliver(unlisted, "failed", DELIVERED_AT, 999999));
        run(noCode, deliver(noCode, "failed", DELIVERED_AT, null));

        assertThat(message(mapped)).isEqualTo("failed|recipient_unreachable||");
        assertThat(message(unlisted)).isEqualTo("failed|meta_999999||");
        assertThat(message(noCode)).isEqualTo("failed|delivery_failed||");
        assertThat(statusEvents(mapped)).containsExactly("failed|recipient_unreachable");
        assertThat(admin.sql("select status || '|' || attempts from northstar.actions where id = :id")
                        .param("id", action)
                        .query(String.class)
                        .single())
                .as("finished actions are never reopened")
                .isEqualTo("succeeded|1");
        assertThat(output.getAll())
                .contains("WARN")
                .contains("webhook.status_failed provider=whatsapp webhook_event_id=" + failed + " message_id="
                        + mapped.message() + " from=sent code=recipient_unreachable");
    }

    @Test
    void aFailureAfterDeliveredOrReadIsIgnored(CapturedOutput output) {
        Sent delivered = sent();
        Sent read = sent();
        run(delivered, deliver(delivered, "delivered", DELIVERED_AT, null));
        run(read, deliver(read, "read", READ_AT, null));

        UUID lateFailure = deliver(delivered, "failed", READ_AT, 131026);
        run(delivered, lateFailure);
        run(read, deliver(read, "failed", READ_AT, 131026));

        assertThat(message(delivered)).isEqualTo("delivered||" + DELIVERED_AT.getEpochSecond() + "|");
        assertThat(message(read)).isEqualTo("read|||" + READ_AT.getEpochSecond());
        assertThat(statusEvents(delivered)).containsExactly("delivered|");
        assertThat(statusEvents(read)).containsExactly("read|");
        assertThat(row(lateFailure)).isEqualTo("true|");
        assertThat(output.getAll())
                .contains("webhook_event_id=" + lateFailure + " message_id=" + delivered.message()
                        + " from=delivered to=failed");
    }

    // A status before its message, the sweep, and the backlog

    @Test
    void aStatusBeforeItsMessageIdIsStoredWaitsAndTheSweepAppliesIt(CapturedOutput output) {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        UUID conversation = data.conversation(tenant, data.contact(tenant), number, null);
        UUID message = queued(tenant, conversation);
        String wamid = WhatsAppPayloads.wamid();
        Sent sent = new Sent(tenant, number, conversation, message, wamid);

        // Meta's webhook beats the send's commit of the ID (step 2.6a).
        UUID early = deliver(sent, "delivered", DELIVERED_AT, null);
        run(sent, early);
        assertThat(row(early)).isEqualTo("false|message_not_found_yet");
        assertThat(message(sent)).isEqualTo("queued|||");
        assertThat(output.getAll())
                .contains("webhook.status_waiting provider=whatsapp webhook_event_id=" + early
                        + " error=message_not_found_yet");

        // The send commits; the sweep's first retry, at 2 minutes, finds the message.
        admin.sql(
                        "update northstar.messages set provider_message_id = :w, status = 'sent', sent_at = now() where id = :id")
                .param("w", wamid)
                .param("id", message)
                .update();
        age(early, "3 minutes");
        sweep(tenant);
        assertThat(retryJobs(early)).containsExactly(1);
        run(sent, early);

        assertThat(row(early)).as("the earlier code is cleared").isEqualTo("true|");
        assertThat(message(sent)).isEqualTo("delivered||" + DELIVERED_AT.getEpochSecond() + "|");
    }

    @Test
    void aStatusNeverMatchedIsClosedAt24HoursWithItsCode(CapturedOutput output) {
        Sent sent = sent();
        Sent never =
                new Sent(sent.tenant(), sent.number(), sent.conversation(), sent.message(), WhatsAppPayloads.wamid());
        UUID orphan = deliver(never, "delivered", DELIVERED_AT, null);
        run(never, orphan);
        assertThat(row(orphan)).isEqualTo("false|message_not_found_yet");

        age(orphan, "25 hours");
        sweep(sent.tenant());

        assertThat(row(orphan)).isEqualTo("true|message_not_found_yet");
        assertThat(output.getAll())
                .contains("ERROR")
                .contains("webhook.failed provider=whatsapp webhook_event_id=" + orphan
                        + " error=message_not_found_yet final=true");
        assertThat(message(sent)).as("the business's own message is untouched").isEqualTo("sent|||");
    }

    @Test
    void statusRowsStoredBeforeStep27AreSweptAndProcessed() {
        Sent sent = sent();
        Sent unknown =
                new Sent(sent.tenant(), sent.number(), sent.conversation(), sent.message(), WhatsAppPayloads.wamid());
        // Stored since step 2.2 and never attempted: processed_at and error null, two days old.
        UUID matched = deliver(sent, "read", READ_AT, null);
        UUID unmatched = deliver(unknown, "delivered", DELIVERED_AT, null);
        age(matched, "2 days");
        age(unmatched, "2 days");

        sweep(sent.tenant());
        assertThat(retryJobs(matched)).as("queued under the last step").containsExactly(4);
        assertThat(retryJobs(unmatched)).containsExactly(4);
        run(sent, matched);
        run(unknown, unmatched);
        sweep(sent.tenant());

        assertThat(message(sent)).isEqualTo("read|||" + READ_AT.getEpochSecond());
        assertThat(row(matched)).isEqualTo("true|");
        assertThat(row(unmatched))
                .as("past 24 hours: closed at the next sweep")
                .isEqualTo("true|message_not_found_yet");
    }

    // Other statuses and unusable slices

    @Test
    void aStatusNorthstarDoesNotTrackIsClosedAndAnUnusableSliceIsRejected() {
        Sent sent = sent();
        UUID played = deliver(sent, "played", DELIVERED_AT, null);
        run(sent, played);

        UUID unusable = admin.sql("""
                        insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)
                        values (:t, 'whatsapp', :key, '{"entry": []}') returning id""")
                .param("t", sent.tenant())
                .param("key", "status:" + sent.wamid() + ":read")
                .query(UUID.class)
                .single();
        run(sent, unusable);

        assertThat(row(played)).isEqualTo("true|status_unsupported");
        assertThat(row(unusable)).isEqualTo("true|payload_invalid");
        assertThat(message(sent)).isEqualTo("sent|||");
        assertThat(statusEvents(sent)).isEmpty();
    }

    // Tenant isolation (P9, P10)

    @Test
    void P9_aStatusRoutedToOneTenantNeverChangesAnotherTenantsMessage() {
        Sent ours = sent();
        Sent theirs = sent();
        // A status on our number that names their message's wamid.
        Sent forged = new Sent(ours.tenant(), ours.number(), ours.conversation(), ours.message(), theirs.wamid());

        UUID row = deliver(forged, "read", READ_AT, null);
        run(ours, row);

        assertThat(row(row)).isEqualTo("false|message_not_found_yet");
        assertThat(message(theirs)).isEqualTo("sent|||");
        assertThat(statusEvents(theirs)).isEmpty();
        assertThat(admin.sql("select count(*) from northstar.events where tenant_id = :t")
                        .param("t", theirs.tenant())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void P9_aJobNamingAnotherTenantsStatusRowFailsLoudlyAndWritesNothing() {
        Sent owner = sent();
        Sent other = sent();
        UUID row = deliver(owner, "delivered", DELIVERED_AT, null);

        assertThatThrownBy(() -> job.run(new ProcessWhatsAppWebhook(other.tenant(), row)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(row(row)).isEqualTo("false|");
        assertThat(message(owner)).isEqualTo("sent|||");
    }

    @Test
    void P10_withoutATenantContextNothingIsRecorded() {
        Sent sent = sent();
        UUID row = deliver(sent, "delivered", DELIVERED_AT, null);
        TestData.Member member = data.member("admin");

        // The job's body run outside the tenant scope TenantJobHandler opens, as a bug would.
        assertThatThrownBy(() -> job.runForTenant(new ProcessWhatsAppWebhook(sent.tenant(), row)))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> outbound.recordStatus(sent.wamid(), DeliveryStatus.READ, READ_AT, null))
                .as("the writer joins a transaction; it never opens one")
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.execute(
                        status -> outbound.recordStatus(sent.wamid(), DeliveryStatus.READ, READ_AT, null)))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                        () -> transactions.execute(status -> events.recordMessageStatus(new EngineEvents.MessageStatus(
                                sent.wamid(),
                                row,
                                UUID.randomUUID(),
                                sent.conversation(),
                                sent.message(),
                                "read",
                                null,
                                READ_AT)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");

        assertThat(row(row)).isEqualTo("false|");
        assertThat(message(sent)).isEqualTo("sent|||");
        assertThat(statusEvents(sent)).isEmpty();
    }

    // Lock order: the webhook row, then the message; never the contact, conversation or action

    @Test
    void twoStatusesOfOneMessageRunTogetherWithoutDeadlockAndEndAtRead() throws Exception {
        for (int round = 0; round < 5; round++) {
            Sent sent = sent();
            UUID delivered = deliver(sent, "delivered", DELIVERED_AT, null);
            UUID read = deliver(sent, "read", READ_AT, null);

            runTogether(sent.tenant(), List.of(delivered, read));

            assertThat(message(sent))
                    .as("round " + round + ": whichever ran first, the message ends read with both times")
                    .isEqualTo("read||" + DELIVERED_AT.getEpochSecond() + "|" + READ_AT.getEpochSecond());
            assertThat(row(delivered)).isEqualTo("true|");
            assertThat(row(read)).isEqualTo("true|");
            assertThat(statusEvents(sent)).containsExactlyInAnyOrder("delivered|", "read|");
        }
    }

    @Test
    void aStatusDuringAnOptOutOfItsContactNeitherWaitsNorDeadlocks() throws Exception {
        Sent sent = sent();
        UUID contact = admin.sql("select contact_id from northstar.conversations where id = :id")
                .param("id", sent.conversation())
                .query(UUID.class)
                .single();
        UUID read = deliver(sent, "read", READ_AT, null);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // The inbound job's opt-out holds the contact, then the conversation (steps 2.4b, 2.5) while the status
            // runs.
            JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
            new TransactionTemplate(new DataSourceTransactionManager(postgres.dataSource()))
                    .executeWithoutResult(status -> {
                        jdbc.sql("select id from northstar.contacts where id = :id for update")
                                .param("id", contact)
                                .query(UUID.class)
                                .single();
                        jdbc.sql("select id from northstar.conversations where id = :id for update")
                                .param("id", sent.conversation())
                                .query(UUID.class)
                                .single();
                        Future<?> applying = worker.submit(() -> run(sent, read));
                        try {
                            applying.get(10, TimeUnit.SECONDS); // a status waiting on either lock would time out here
                        } catch (Exception e) {
                            throw new AssertionError("The status waited for the contact's or conversation's lock", e);
                        }
                    });
        } finally {
            worker.shutdownNow();
        }

        assertThat(message(sent)).isEqualTo("read|||" + READ_AT.getEpochSecond());
    }

    // What is recorded and logged

    @Test
    void eventsHoldIdsCodesAndMetasTimeOnly() {
        Sent sent = sent();
        UUID row = deliver(sent, "failed", DELIVERED_AT, 131026);
        run(sent, row);

        assertThat(admin.sql("""
                        select string_agg(key, ',' order by key)
                        from northstar.events e, jsonb_object_keys(e.payload) key
                        where e.type = 'message.status' and e.payload ->> 'message_id' = :m""")
                        .param("m", sent.message().toString())
                        .query(String.class)
                        .single())
                .isEqualTo("contact_id,conversation_id,error_code,message_id,status,status_at,webhook_event_id");
        assertThat(admin.sql("""
                        select dedupe_key || '|' || (lead_id is null) || '|' || (payload ->> 'webhook_event_id')
                               || '|' || (payload ->> 'status_at')
                        from northstar.events where type = 'message.status' and payload ->> 'message_id' = :m""")
                        .param("m", sent.message().toString())
                        .query(String.class)
                        .single())
                .isEqualTo("message.status:" + sent.wamid() + ":failed|true|" + row + "|" + DELIVERED_AT);
    }

    @Test
    void logsCarryIdsStatusesAndCodesOnly(CapturedOutput output) {
        Sent sent = sent();
        String echo = WhatsAppPayloads.marker();
        run(sent, deliver(sent, "delivered", DELIVERED_AT, null));
        run(sent, deliver(sent, "sent", DELIVERED_AT, null));
        Sent failing = sent();
        run(failing, deliver(failing, "failed", DELIVERED_AT, 131026, echo));

        assertThat(output.getAll())
                .contains("webhook.status_applied", "webhook.status_ignored", "webhook.status_failed")
                .doesNotContain(sent.wamid(), failing.wamid())
                .doesNotContain("910000000001")
                .doesNotContain(echo)
                .doesNotContain("Message undeliverable");
    }

    // Helpers.

    /** A business's outbound message that Meta accepted (step 2.6a): sent, with its wamid. */
    private record Sent(UUID tenant, String number, UUID conversation, UUID message, String wamid) {}

    private Sent sent() {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        UUID conversation = data.conversation(tenant, data.contact(tenant), number, null);
        String wamid = WhatsAppPayloads.wamid();
        UUID message = admin.sql("""
                        insert into northstar.messages
                          (tenant_id, conversation_id, direction, sender_type, provider_message_id, type, body, status,
                           sent_at)
                        values (:t, :c, 'out', 'system', :w, 'text', 'Your shoot is booked', 'sent', now())
                        returning id""")
                .param("t", tenant)
                .param("c", conversation)
                .param("w", wamid)
                .query(UUID.class)
                .single();
        return new Sent(tenant, number, conversation, message, wamid);
    }

    private UUID queued(UUID tenant, UUID conversation) {
        return admin.sql("""
                        insert into northstar.messages (tenant_id, conversation_id, direction, sender_type, type, body, status)
                        values (:t, :c, 'out', 'system', 'text', 'Your shoot is booked', 'queued') returning id""")
                .param("t", tenant)
                .param("c", conversation)
                .query(UUID.class)
                .single();
    }

    /** The send that sent this message, finished (step 2.6a). */
    private UUID succeededSend(Sent sent) {
        UUID contact = admin.sql("select contact_id from northstar.conversations where id = :id")
                .param("id", sent.conversation())
                .query(UUID.class)
                .single();
        return admin.sql("""
                        insert into northstar.actions
                          (tenant_id, type, status, idempotency_key, mode, payload, recipient_contact_id, attempts)
                        values (:t, 'whatsapp.send', 'succeeded', :k, 'automatic', cast(:p as jsonb), :c, 1)
                        returning id""")
                .param("t", sent.tenant())
                .param("k", "whatsapp-send:" + UUID.randomUUID())
                .param("p", WhatsAppSendHandler.payloadFor(sent.message()))
                .param("c", contact)
                .query(UUID.class)
                .single();
    }

    /** Delivers a status for the message through the real intake and returns its webhook row. */
    private UUID deliver(Sent sent, String status, Instant at, Integer errorCode) {
        return deliver(sent, status, at, errorCode, "details");
    }

    private UUID deliver(Sent sent, String status, Instant at, Integer errorCode, String echo) {
        intake.receive(WhatsAppPayloads.delivery()
                .status(sent.number(), sent.wamid(), status, at, errorCode, echo)
                .json()
                .getBytes(StandardCharsets.UTF_8));
        return admin.sql("select id from northstar.webhook_events where provider_event_id = :k")
                .param("k", "status:" + sent.wamid() + ":" + status)
                .query(UUID.class)
                .single();
    }

    private void run(Sent sent, UUID row) {
        job.run(new ProcessWhatsAppWebhook(sent.tenant(), row));
    }

    /** Runs the rows' jobs on their own threads, all released at once. */
    private void runTogether(UUID tenant, List<UUID> rows) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(rows.size());
        CountDownLatch ready = new CountDownLatch(rows.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (UUID row : rows) {
                runs.add(threads.submit(() -> {
                    ready.countDown();
                    go.await();
                    job.run(new ProcessWhatsAppWebhook(tenant, row));
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> one : runs) one.get(60, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
    }

    private void sweep(UUID tenant) {
        sweeper.run(new SweepTenantWebhooks(tenant));
    }

    private void age(UUID row, String age) {
        admin.sql("update northstar.webhook_events set received_at = now() - cast(:age as interval) where id = :id")
                .param("age", age)
                .param("id", row)
                .update();
    }

    private void replay(UUID row) {
        admin.sql("update northstar.webhook_events set processed_at = null where id = :id")
                .param("id", row)
                .update();
    }

    /** The retry steps the sweep queued for this row. */
    private List<Integer> retryJobs(UUID row) {
        return IntStream.rangeClosed(1, WebhookRetrySchedule.STEPS.size())
                .filter(step -> admin.sql("select count(*) from jobrunr.jobrunr_jobs where id = :id")
                                .param(
                                        "id",
                                        ProcessWhatsAppWebhook.retryJobIdFor(row, step)
                                                .toString())
                                .query(Long.class)
                                .single()
                        > 0)
                .boxed()
                .toList();
    }

    /** status|error_code|delivered_at (epoch seconds)|read_at (epoch seconds) */
    private String message(Sent sent) {
        return admin.sql("""
                        select status || '|' || coalesce(error_code, '') || '|'
                               || coalesce(extract(epoch from delivered_at)::bigint::text, '') || '|'
                               || coalesce(extract(epoch from read_at)::bigint::text, '')
                        from northstar.messages where id = :id""").param("id", sent.message()).query(String.class).single();
    }

    /** processed|error */
    private String row(UUID row) {
        return admin.sql("""
                        select (processed_at is not null) || '|' || coalesce(error, '')
                        from northstar.webhook_events where id = :id""").param("id", row).query(String.class).single();
    }

    /** status|error_code of the message's message.status events, oldest first. */
    private List<String> statusEvents(Sent sent) {
        return admin.sql("""
                        select (payload ->> 'status') || '|' || coalesce(payload ->> 'error_code', '')
                        from northstar.events
                        where type = 'message.status' and payload ->> 'message_id' = :m
                        order by received_at, id""")
                .param("m", sent.message().toString())
                .query(String.class)
                .list();
    }
}
