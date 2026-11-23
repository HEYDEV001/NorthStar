package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.contacts.OutboundMessages;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.fakes.WhatsAppPayloads.Inbound;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.jobs.SystemScope;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * W32's one exception (step 2.9b, workflow-spec.md section 13 Opt-out): a contact who opted out and then writes again
 * may get a staff member's free-text reply inside the 24-hour window that message opens, audited, and nothing else.
 * Every opt-out and client message goes through the real inbound job (steps 2.4b and 2.5), and every send through the
 * worker's claim, so the app's check and the database's backstop (NSA01) are both exercised. The rule itself, row by
 * row, is in ConsentSchemaTests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, WhatsAppSendTests.WorkerCredentials.class})
class OptOutReplyTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final WhatsAppMessages messages;
    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler inboundJob;
    private final ExecuteActionHandler executeAction;
    private final FakeWhatsAppSender whatsApp;
    private final CredentialStore credentials;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    OptOutReplyTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired WhatsAppMessages messages,
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler inboundJob,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeWhatsAppSender whatsApp,
            @Autowired CredentialStore credentials,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.messages = messages;
        this.intake = intake;
        this.inboundJob = inboundJob;
        this.executeAction = executeAction;
        this.whatsApp = whatsApp;
        this.credentials = credentials;
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void W32_anOptedOutContactWhoHasNotWrittenSinceGetsNoReply() {
        Business business = business();
        Instant now = now();
        inbound(business, now.minus(10, ChronoUnit.MINUTES), "Need a shoot");
        inbound(business, now.minus(5, ChronoUnit.MINUTES), "STOP");

        assertThat(reply(business, text("Sorry to see you go")))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("contact_opted_out");

        JsonNode conversation = conversationView(business);
        assertThat(conversation.get("contact").get("consentState").stringValue())
                .isEqualTo("opted_out");
        assertThat(conversation.get("replyAfterOptOutUntil").isNull()).isTrue();
        assertThat(outboundCount(business)).isZero();
    }

    @Test
    void W32_aContactWhoWritesAgainMayGetAStaffTextReplyWhichIsAuditedAndSent() {
        Business business = business();
        Instant now = now();
        Instant again = now.minus(5, ChronoUnit.MINUTES);
        inbound(business, now.minus(10, ChronoUnit.MINUTES), "STOP");
        inbound(business, again, "Actually, one question");

        JsonNode conversation = conversationView(business);
        assertThat(conversation.get("replyAfterOptOutUntil").stringValue())
                .isEqualTo(again.plus(24, ChronoUnit.HOURS).toString());

        MvcTestResult result = reply(business, text("Happy to help"));

        assertThat(result).hasStatus(201);
        UUID messageId = id(result);
        assertThat(admin.sql("""
                        select action || '|' || target_table || '|' || target_id || '|' || actor_user_id
                        from northstar.audit_log where tenant_id = :t and action = 'message.sent_after_opt_out'""")
                        .param("t", business.tenant())
                        .query(String.class)
                        .single())
                .isEqualTo("message.sent_after_opt_out|messages|" + messageId + "|" + business.staffUser());

        send(business, messageId);

        assertThat(whatsApp.sent(messageId))
                .as("past the claim and the database's backstop")
                .hasSize(1);
        assertThat(messageStatus(messageId)).isEqualTo("sent");
        assertThat(consent(business)).as("consent stays opted out").isEqualTo("opted_out");
    }

    @Test
    void W32_templatesStayRefusedForAnOptedOutContactInsideTheWindowOrOutsideIt() {
        Business business = business();
        UUID template = template(business.tenant());
        Instant now = now();
        inbound(business, now.minus(10, ChronoUnit.MINUTES), "STOP");
        inbound(business, now.minus(5, ChronoUnit.MINUTES), "Hello again");

        assertThat(reply(business, templateReply(template)))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("contact_opted_out");

        Business old = business();
        inbound(old, now.minus(50, ChronoUnit.HOURS), "STOP");
        inbound(old, now.minus(30, ChronoUnit.HOURS), "Hello again");
        assertThat(reply(old, templateReply(template(old.tenant()))))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("contact_opted_out");
        assertThat(outboundCount(business) + outboundCount(old)).isZero();
    }

    @Test
    void W32_aiAndSystemSendsStayRefusedEvenWhileTheExceptionIsOpen() {
        Business business = business();
        Instant now = now();
        inbound(business, now.minus(10, ChronoUnit.MINUTES), "STOP");
        inbound(business, now.minus(5, ChronoUnit.MINUTES), "Hello again");

        for (OutboundMessages.Sender sender : OutboundMessages.Sender.values()) {
            WhatsAppMessages.Queued queued = SystemScope.call(
                    business.tenant(),
                    () -> messages.queueText(
                            business.conversation(), "Automated", sender, "test:" + UUID.randomUUID()));
            assertThat(queued.action().status()).as(sender.name()).isEqualTo("cancelled");
            assertThat(admin.sql("select status || '|' || error_code from northstar.messages where id = :id")
                            .param("id", queued.messageId())
                            .query(String.class)
                            .single())
                    .isEqualTo("failed|contact_opted_out");
        }
        assertThat(reply(business, text("A person writing"))).hasStatus(201);
    }

    @Test
    void W32_aSecondStopCancelsAPendingReplyAndClosesTheExceptionAgain() {
        Business business = business();
        Instant now = now();
        Instant first = now.minus(10, ChronoUnit.MINUTES);
        Instant second = now.minus(1, ChronoUnit.MINUTES);
        inbound(business, first, "STOP");
        inbound(business, now.minus(5, ChronoUnit.MINUTES), "Hello again");
        UUID pending = id(reply(business, text("Queued before the second STOP")));

        inbound(business, second, "stop");

        assertThat(admin.sql("""
                        select a.status || '|' || a.cancel_reason || '|' || m.status || '|' || m.error_code
                        from northstar.actions a join northstar.messages m on m.id = (a.payload ->> 'messageId')::uuid
                        where m.id = :id""").param("id", pending).query(String.class).single())
                .isEqualTo("cancelled|contact_opted_out|failed|contact_opted_out");
        assertThat(reply(business, text("After the second STOP")))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("contact_opted_out");
        assertThat(conversationView(business).get("replyAfterOptOutUntil").isNull())
                .isTrue();
        assertThat(admin.sql("select opt_out_at || '|' || last_opt_out_at from northstar.contacts where id = :id")
                        .param("id", contact(business))
                        .query(String.class)
                        .single())
                .as("the first opt-out kept, the latest moved")
                .isEqualTo(utc(first) + "|" + utc(second));
    }

    @Test
    void W32_aLateOptOutOlderThanTheClientsMessageLeavesTheExceptionOpen() {
        Business business = business();
        Instant now = now();
        inbound(business, now.minus(10, ChronoUnit.MINUTES), "STOP");
        inbound(business, now.minus(3, ChronoUnit.MINUTES), "Hello again");
        inbound(business, now.minus(7, ChronoUnit.MINUTES), "STOP"); // delivered late, sent before "Hello again"

        assertThat(reply(business, text("Still allowed"))).hasStatus(201);
    }

    @Test
    void W32_reactionsSystemMessagesAndAMessageInTheSameSecondOpenNothing() {
        Business business = business();
        Instant now = now();
        Instant stop = now.minus(10, ChronoUnit.MINUTES);
        inbound(business, stop, "STOP");
        inbound(business, stop, "Same second as the STOP");
        store(
                business,
                WhatsAppPayloads.inbound(business.number())
                        .sentAt(now.minus(5, ChronoUnit.MINUTES))
                        .typed("reaction", "{\"message_id\":\"wamid.REACTED\",\"emoji\":\"\\u2764\"}"));
        store(
                business,
                WhatsAppPayloads.inbound(business.number())
                        .sentAt(now.minus(4, ChronoUnit.MINUTES))
                        .typed("system", "{\"body\":\"Number changed\",\"type\":\"customer_changed_number\"}"));

        assertThat(reply(business, text("Not yet"))).hasStatus(409);

        inbound(business, now.minus(3, ChronoUnit.MINUTES), "A real message");
        assertThat(reply(business, text("Now"))).hasStatus(201);
    }

    @Test
    void W32_theExceptionEndsTwentyFourHoursAfterTheClientWroteWithTheSendersMargin() {
        Business business = business();
        Instant now = now();
        inbound(business, now.minus(30, ChronoUnit.HOURS), "STOP");
        inbound(business, now.minus(Duration.ofHours(24).minusMinutes(1)), "One minute left");

        assertThat(reply(business, text("Inside the margin")))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("contact_opted_out");

        // A reply queued in time, whose send runs only once the window has passed, is cancelled at its claim.
        Business late = business();
        inbound(late, now.minus(30, ChronoUnit.HOURS), "STOP");
        inbound(late, now.minus(1, ChronoUnit.HOURS), "Hello again");
        UUID queued = id(reply(late, text("Sent too late")));
        admin.sql("""
                        update northstar.conversations set last_contact_message_at = now() - interval '24 hours'
                          + interval '1 minute' where id = :id""").param("id", late.conversation()).update();

        send(late, queued);

        assertThat(whatsApp.sent(queued)).isEmpty();
        assertThat(admin.sql("""
                        select a.status || '|' || a.cancel_reason || '|' || m.status || '|' || m.error_code
                        from northstar.actions a join northstar.messages m on m.id = (a.payload ->> 'messageId')::uuid
                        where m.id = :id""").param("id", queued).query(String.class).single())
                .isEqualTo("cancelled|contact_opted_out|failed|contact_opted_out");
    }

    @Test
    void W32_aReplyDuringAnOptOutWaitsForItThenIsRefusedAndNeverDeadlocks() throws Exception {
        Business business = business();
        Instant now = now();
        inbound(business, now.minus(10, ChronoUnit.MINUTES), "STOP");
        inbound(business, now.minus(5, ChronoUnit.MINUTES), "Hello again");
        UUID contact = contact(business);

        for (int round = 0; round < 5; round++) {
            // The client writes again (as the inbound job would record it), so the exception is open for this round.
            admin.sql("update northstar.conversations set last_contact_message_at = now() where id = :id")
                    .param("id", business.conversation())
                    .update();
            AtomicReference<Future<MvcTestResult>> replying = new AtomicReference<>();
            optOutInProgress(contact, jdbc -> {
                replying.set(threads.submit(() -> reply(business, text("Racing the STOP"))));
                awaitWaitingForTheContact();
                jdbc.sql("select id from northstar.conversations where id = :id for update")
                        .param("id", business.conversation())
                        .query(UUID.class)
                        .single();
                return jdbc.sql("""
                                update northstar.actions
                                set status = 'cancelled', cancel_reason = 'contact_opted_out', next_attempt_at = null
                                where recipient_contact_id = :c and status in ('pending', 'needs_review', 'paused')""").param("c", contact).update();
            });
            assertThat(replying.get().get(30, TimeUnit.SECONDS))
                    .as("round " + round)
                    .hasStatus(409)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("contact_opted_out");
        }
        assertThat(outboundCount(business)).isZero();
    }

    // Helpers.

    private record Business(UUID tenant, String number, UUID staffUser, UUID staffMembership, UUID conversation) {}

    /**
     * A connected business whose customer (WhatsAppPayloads' fake one) has a conversation assigned to a staff member,
     * opened by a first message long ago, so every test's own messages are newer.
     */
    private Business business() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(tenant, staffUser, "staff", "active");
        String number = data.whatsAppNumber(tenant);
        connect(tenant);
        store(
                tenant,
                WhatsAppPayloads.inbound(number)
                        .sentAt(now().minus(60, ChronoUnit.HOURS))
                        .text("First"));
        UUID conversation = admin.sql("select id from northstar.conversations where tenant_id = :t")
                .param("t", tenant)
                .query(UUID.class)
                .single();
        admin.sql("update northstar.conversations set assigned_membership_id = :m where id = :id")
                .param("m", staffMembership)
                .param("id", conversation)
                .update();
        return new Business(tenant, number, staffUser, staffMembership, conversation);
    }

    private void connect(UUID tenant) {
        UUID credential = UUID.randomUUID();
        SystemScope.call(tenant, () -> {
            try (Secret secret = Secret.ofUtf8("EAAG-test-token-" + UUID.randomUUID())) {
                return credentials.insertIfAbsent(credential, "whatsapp", secret, null);
            }
        });
        admin.sql("""
                        insert into northstar.integrations (tenant_id, provider, status, credential_id)
                        values (:t, 'whatsapp', 'connected', :c)""").param("t", tenant).param("c", credential).update();
    }

    private UUID template(UUID tenant) {
        return admin.sql("""
                        insert into northstar.message_templates
                          (tenant_id, name, language, category, body, variables, status)
                        values (:t, :name, 'en_US', 'UTILITY', 'Thanks for writing', '{"format": "positional",
                                "names": []}', 'approved')
                        returning id""")
                .param("t", tenant)
                .param("name", "reply_" + UUID.randomUUID().toString().replace("-", ""))
                .query(UUID.class)
                .single();
    }

    /** A text message from the customer, processed by the real inbound job: "STOP" opts out (step 2.5). */
    private void inbound(Business business, Instant sentAt, String text) {
        store(
                business,
                WhatsAppPayloads.inbound(business.number()).sentAt(sentAt).text(text));
    }

    private void store(Business business, Inbound message) {
        store(business.tenant(), message);
    }

    /** Stores the message through the real intake (step 2.2) and runs the inbound job on it as the worker does. */
    private void store(UUID tenant, Inbound message) {
        intake.receive(message.json().getBytes(StandardCharsets.UTF_8));
        UUID row = admin.sql(
                        "select id from northstar.webhook_events where provider = 'whatsapp' and provider_event_id = :e")
                .param("e", "message:" + message.wamid())
                .query(UUID.class)
                .single();
        asWorker(() -> inboundJob.run(new ProcessWhatsAppWebhook(tenant, row)));
    }

    private MvcTestResult reply(Business business, String json) {
        return mvc.post()
                .uri("/api/v1/conversations/" + business.conversation() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(business.staffUser()))
                .header(LogContext.REQUEST_ID_HEADER, "opt-out-reply-test")
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private JsonNode conversationView(Business business) {
        MvcTestResult result = mvc.get()
                .uri("/api/v1/conversations/" + business.conversation())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(business.staffUser()))
                .exchange();
        assertThat(result).hasStatusOk();
        return JSON.readTree(body(result));
    }

    private static String text(String text) {
        return JSON.createObjectNode().put("type", "text").put("text", text).toString();
    }

    private static String templateReply(UUID template) {
        return JSON.createObjectNode()
                .put("type", "template")
                .put("templateId", template.toString())
                .toString();
    }

    /** Runs the reply's send as the worker does: its claim reads consent, and the database checks it again. */
    private void send(Business business, UUID messageId) {
        UUID action = admin.sql("select id from northstar.actions where payload ->> 'messageId' = :m")
                .param("m", messageId.toString())
                .query(UUID.class)
                .single();
        asWorker(() -> executeAction.run(new ExecuteAction(business.tenant(), action)));
    }

    /** Spring's test support binds a mock request to the test thread; the worker's system actor is refused on one. */
    private static void asWorker(Runnable work) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            work.run();
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    /**
     * Runs the steps in one transaction that first locks the contact for update and records another opt-out, as the
     * inbound job does (steps 2.4b, 2.5 and 2.9b), and commits once they return. Every lock wait gives up after 3s.
     */
    private <T> T optOutInProgress(UUID contact, Function<JdbcClient, T> steps) {
        JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
        return new TransactionTemplate(new DataSourceTransactionManager(postgres.dataSource())).execute(status -> {
            jdbc.sql("set local lock_timeout = '3s'").update();
            jdbc.sql("select id from northstar.contacts where id = :id for update")
                    .param("id", contact)
                    .query(UUID.class)
                    .single();
            jdbc.sql("update northstar.contacts set last_opt_out_at = now() where id = :id")
                    .param("id", contact)
                    .update();
            return steps.apply(jdbc);
        });
    }

    /** Until another session waits for a lock on a shared read of a contact. */
    private void awaitWaitingForTheContact() {
        await().atMost(WAIT).until(() -> admin.sql("""
                                select count(*) from pg_stat_activity
                                where wait_event_type = 'Lock' and query like '%northstar.contacts%for share%'""").query(Long.class).single() == 1);
    }

    private UUID contact(Business business) {
        return admin.sql("select contact_id from northstar.conversations where id = :id")
                .param("id", business.conversation())
                .query(UUID.class)
                .single();
    }

    private String consent(Business business) {
        return admin.sql("select consent_state from northstar.contacts where id = :id")
                .param("id", contact(business))
                .query(String.class)
                .single();
    }

    private String messageStatus(UUID messageId) {
        return admin.sql("select status from northstar.messages where id = :id")
                .param("id", messageId)
                .query(String.class)
                .single();
    }

    private long outboundCount(Business business) {
        return admin.sql("""
                        select count(*) from northstar.messages
                        where tenant_id = :t and direction = 'out' and sender_type = 'staff'""").param("t", business.tenant()).query(Long.class).single();
    }

    private static UUID id(MvcTestResult result) {
        return UUID.fromString(JSON.readTree(body(result)).get("id").stringValue());
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Postgres's text form of a timestamptz in a UTC session, as the opt-out columns read back. */
    private String utc(Instant at) {
        return admin.sql("select cast(:at as timestamptz)::text")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(String.class)
                .single();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
