package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.contacts.OutboundContent;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A staff member's reply (step 2.9b) through the real login path and the real send, against Postgres with RLS on and
 * the fake Cloud API: free text inside the 24-hour window and an approved template outside it (the window read again
 * when the send runs, W33), the sender recorded as the caller's membership, one message and one send for a request sent
 * twice (P21), who may reply where (P9, P11), and a closed conversation reopened. Replies to opted-out contacts are
 * {@link OptOutReplyTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, WhatsAppSendTests.WorkerCredentials.class})
@ExtendWith(OutputCaptureExtension.class)
class StaffReplyTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final WhatsAppMessages messages;
    private final ExecuteActionHandler executeAction;
    private final FakeWhatsAppSender whatsApp;
    private final CredentialStore credentials;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    StaffReplyTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired WhatsAppMessages messages,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeWhatsAppSender whatsApp,
            @Autowired CredentialStore credentials,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.messages = messages;
        this.executeAction = executeAction;
        this.whatsApp = whatsApp;
        this.credentials = credentials;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    // Inside and outside the window

    @Test
    void aReplyInsideTheWindowIsQueuedAsTheMembersOwnAndSent() {
        Business business = business();

        MvcTestResult result = reply(business.staffUser(), business.conversation(), text("On it, Asha"), key());

        assertThat(result).hasStatus(201);
        JsonNode message = json(result);
        assertThat(message.get("direction").stringValue()).isEqualTo("out");
        assertThat(message.get("senderType").stringValue()).isEqualTo("staff");
        assertThat(message.get("senderMembershipId").stringValue())
                .isEqualTo(business.staffMembership().toString());
        assertThat(message.get("type").stringValue()).isEqualTo("text");
        assertThat(message.get("body").stringValue()).isEqualTo("On it, Asha");
        assertThat(message.get("status").stringValue()).isEqualTo("queued");
        UUID messageId = UUID.fromString(message.get("id").stringValue());
        assertThat(action(messageId))
                .as("a send to the contact, the message ID its only payload")
                .isEqualTo("whatsapp.send|pending|" + business.contact());

        send(business, messageId);

        assertThat(whatsApp.sent(messageId)).singleElement().satisfies(sent -> {
            assertThat(sent.to()).isEqualTo(business.phone());
            assertThat(sent.body()).isEqualTo(new WhatsAppSender.Text("On it, Asha"));
        });
        assertThat(messageStatus(messageId)).isEqualTo("sent");
    }

    @Test
    void anAdminRepliesInAnyConversationAsTheirOwnMembership() {
        Business business = business();

        JsonNode message = json(reply(business.adminUser(), business.conversation(), text("From the owner"), key()));

        assertThat(message.get("senderMembershipId").stringValue())
                .isEqualTo(business.adminMembership().toString());
    }

    @Test
    void outsideTheWindowOnlyAnApprovedTemplateWithItsValuesIsSent() {
        Business business = business();
        inboundAt(business, "now() - interval '25 hours'");
        UUID template = template(business.tenant(), "approved");

        assertThat(reply(business.staffUser(), business.conversation(), text("Too late for text"), key()))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("window_closed");
        assertThat(messageCount(business)).as("nothing stored").isZero();

        MvcTestResult result =
                reply(business.staffUser(), business.conversation(), templateReply(template, "Asha"), key());

        assertThat(result).hasStatus(201);
        JsonNode message = json(result);
        assertThat(message.get("type").stringValue()).isEqualTo("template");
        assertThat(message.get("body").stringValue()).as("the text as rendered").isEqualTo("Hello Asha, see you soon");
        assertThat(message.get("template").get("id").stringValue()).isEqualTo(template.toString());
        UUID messageId = UUID.fromString(message.get("id").stringValue());
        send(business, messageId);
        assertThat(whatsApp.sent(messageId))
                .singleElement()
                .satisfies(sent -> assertThat(sent.body()).isInstanceOf(WhatsAppSender.Template.class));
    }

    @Test
    void insideTheWindowOnlyFreeTextIsSentAndTheMarginDecidesTheEdge() {
        Business business = business();
        UUID template = template(business.tenant(), "approved");

        assertThat(reply(business.staffUser(), business.conversation(), templateReply(template, "Asha"), key()))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("window_open_use_text");

        // One minute left is inside the sender's two-minute margin: the window counts as closed.
        inboundAt(business, "now() - interval '24 hours' + interval '1 minute'");
        assertThat(reply(business.staffUser(), business.conversation(), text("Just in time?"), key()))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("window_closed");
        assertThat(reply(business.staffUser(), business.conversation(), templateReply(template, "Asha"), key()))
                .hasStatus(201);

        inboundAt(business, "now() - interval '24 hours' + interval '3 minutes'");
        assertThat(reply(business.staffUser(), business.conversation(), text("Three minutes left"), key()))
                .hasStatus(201);
    }

    @Test
    void aTemplateThatIsNotApprovedOrValuesThatDoNotFitAreRefusedAndNothingIsStored() {
        Business business = business();
        inboundAt(business, "now() - interval '25 hours'");
        UUID paused = template(business.tenant(), "paused");
        UUID approved = template(business.tenant(), "approved");
        UUID theirs = template(data.tenant(), "approved");

        assertThat(reply(business.staffUser(), business.conversation(), templateReply(paused, "Asha"), key()))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("template_not_approved");
        for (String body : List.of(
                templateReply(approved),
                templateReply(approved, "Asha", "Extra"),
                "{\"type\": \"template\", \"templateId\": \"" + approved + "\", \"namedValues\": {\"name\": \"A\"}}")) {
            assertThat(reply(business.staffUser(), business.conversation(), body, key()))
                    .as(body)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("template_values_mismatch");
        }
        assertThat(reply(business.staffUser(), business.conversation(), templateReply(theirs, "Asha"), key()))
                .as("P9: another business's template")
                .hasStatus(404);
        assertThat(messageCount(business)).isZero();
    }

    @Test
    void W33_aReplyWhoseWindowClosesBeforeItIsSentFailsWithAClearReason() {
        Business business = business();
        UUID messageId = id(reply(business.staffUser(), business.conversation(), text("Sent later"), key()));

        inboundAt(business, "now() - interval '25 hours'");
        send(business, messageId);

        assertThat(whatsApp.sent(messageId)).isEmpty();
        assertThat(admin.sql("select status || '|' || error_code from northstar.messages where id = :id")
                        .param("id", messageId)
                        .query(String.class)
                        .single())
                .isEqualTo("failed|window_closed_no_template");
    }

    // P21: the same request twice

    @Test
    void P21_theSameRequestTwiceQueuesOneMessageAndSendsItOnce() {
        Business business = business();
        String key = key();

        MvcTestResult first = reply(business.staffUser(), business.conversation(), text("Only once"), key);
        MvcTestResult second = reply(business.staffUser(), business.conversation(), text("Only once"), key);

        assertThat(first).hasStatus(201);
        assertThat(second).as("the same message again").hasStatus(200);
        assertThat(id(second)).isEqualTo(id(first));
        assertThat(messageCount(business)).isOne();
        assertThat(actionCount(business)).isOne();
        send(business, id(first));
        send(business, id(first));
        assertThat(whatsApp.sent(id(first))).hasSize(1);
    }

    @Test
    void P21_aDoubleSubmitAtOnceQueuesOneMessage() throws Exception {
        Business business = business();
        String key = key();
        int tabs = 5;
        CountDownLatch ready = new CountDownLatch(tabs);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcTestResult>> submits = new ArrayList<>();
        for (int i = 0; i < tabs; i++) {
            submits.add(threads.submit(() -> {
                ready.countDown();
                go.await();
                return reply(business.staffUser(), business.conversation(), text("Double click"), key);
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        List<Integer> statuses = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (Future<MvcTestResult> submit : submits) {
            MvcTestResult result = submit.get(60, TimeUnit.SECONDS);
            statuses.add(result.getResponse().getStatus());
            ids.add(id(result));
        }
        assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 200);
        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(messageCount(business)).isOne();
        assertThat(actionCount(business)).isOne();
    }

    @Test
    void P21_aKeyUsedForAnotherReplyIs422AndKeysBelongToOneMember() {
        Business business = business();
        UUID other = data.conversation(
                business.tenant(), data.contact(business.tenant()), business.number(), business.staffMembership());
        inboundAt(other, "now() - interval '1 hour'");
        String key = key();
        UUID first = id(reply(business.staffUser(), business.conversation(), text("First"), key));

        for (MvcTestResult reused : List.of(
                reply(business.staffUser(), business.conversation(), text("Something else"), key),
                reply(business.staffUser(), other, text("First"), key))) {
            assertThat(reused)
                    .hasStatus(422)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("idempotency_key_reused");
        }
        MvcTestResult byAdmin = reply(business.adminUser(), business.conversation(), text("First"), key);
        assertThat(byAdmin).as("another member's request with the same key").hasStatus(201);
        assertThat(id(byAdmin)).isNotEqualTo(first);
        assertThat(messageCount(business)).isEqualTo(2);
    }

    @Test
    void aReplyNeedsAWellFormedKeyAndAReplyThatFitsItsType() {
        Business business = business();
        UUID template = UUID.randomUUID();
        assertThat(reply(business.staffUser(), business.conversation(), text("No key"), null))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("idempotency_key_required");
        for (String bad : List.of("has space", "", "x".repeat(101), "semi;colon")) {
            assertThat(reply(business.staffUser(), business.conversation(), text("Bad key"), bad))
                    .as(bad)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("idempotency_key_invalid");
        }
        assertThat(reply(business.staffUser(), business.conversation(), text("Good"), "a".repeat(100)))
                .hasStatus(201);

        for (String body : List.of(
                "{\"type\": \"text\", \"text\": \"  \"}",
                "{\"type\": \"text\"}",
                "{\"type\": \"text\", \"text\": \"Hi\", \"templateId\": \"" + template + "\"}",
                "{\"type\": \"template\"}",
                "{\"type\": \"template\", \"templateId\": \"" + template + "\", \"text\": \"Hi\"}",
                "{\"type\": \"template\", \"templateId\": \"" + template
                        + "\", \"values\": [\"a\"], \"namedValues\": {\"a\": \"b\"}}")) {
            assertThat(reply(business.staffUser(), business.conversation(), body, key()))
                    .as(body)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("reply_invalid");
        }
        for (String body : List.of(
                "{\"text\": \"No type\"}",
                "{\"type\": \"image\", \"text\": \"Hi\"}",
                "{\"type\": \"text\", \"text\": \"Nul \\u0000 here\"}",
                "{\"type\": \"template\", \"templateId\": \"" + template + "\", \"namedValues\": {\"Name\": \"b\"}}",
                "{\"type\": \"template\", \"templateId\": \"" + template + "\", \"values\": [null]}")) {
            assertThat(reply(business.staffUser(), business.conversation(), body, key()))
                    .as(body)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("validation_failed");
        }
        assertThat(reply(business.staffUser(), business.conversation(), text("a".repeat(4097)), key()))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("text_too_long");
        assertThat(reply(business.staffUser(), business.conversation(), text("😀".repeat(4096)), key()))
                .as("4,096 characters, each two UTF-16 units")
                .hasStatus(201);
        assertThat(messageCount(business)).isEqualTo(2);
    }

    // Who may reply where

    @Test
    void P11_staffCannotReplyInAnotherMembersConversationOrAnUnassignedOne() throws Exception {
        Business business = business();
        UUID colleagues = data.conversation(
                business.tenant(), data.contact(business.tenant()), business.number(), business.otherMembership());
        UUID unassigned =
                data.conversation(business.tenant(), data.contact(business.tenant()), business.number(), null);
        for (UUID conversation : List.of(colleagues, unassigned)) inboundAt(conversation, "now() - interval '1 hour'");

        String missing = body(reply(business.staffUser(), UUID.randomUUID(), text("Hi"), "same-key"));
        for (UUID conversation : List.of(colleagues, unassigned)) {
            MvcTestResult refused = reply(business.staffUser(), conversation, text("Hi"), "same-key");
            assertThat(refused).hasStatus(404);
            assertThat(body(refused)).isEqualTo(missing);
        }
        assertThat(admin.sql("select count(*) from northstar.messages where tenant_id = :t")
                        .param("t", business.tenant())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void P9_aReplyToAnotherTenantsConversationIsTheSame404AsAMissingOne() throws Exception {
        Business ours = business();
        Business theirs = business();

        MvcTestResult foreign = reply(ours.adminUser(), theirs.conversation(), text("Hi"), "p9-key");
        MvcTestResult missing = reply(ours.adminUser(), UUID.randomUUID(), text("Hi"), "p9-key");

        assertThat(foreign).hasStatus(404);
        assertThat(body(foreign)).isEqualTo(body(missing));
        assertThat(messageCount(theirs)).isZero();
    }

    // Closed conversations

    @Test
    void aStaffReplyReopensAClosedConversationAndMovesItsLatestActivity() {
        Business business = business();
        admin.sql("""
                        update northstar.conversations set status = 'closed', last_message_at = now() - interval '1 day'
                        where id = :id""").param("id", business.conversation()).update();

        UUID messageId = id(reply(business.staffUser(), business.conversation(), text("Back to it"), key()));

        assertThat(admin.sql("""
                        select c.status || '|' || (c.last_message_at = m.created_at)
                        from northstar.conversations c join northstar.messages m on m.conversation_id = c.id
                        where m.id = :id""").param("id", messageId).query(String.class).single())
                .isEqualTo("open|true");
    }

    @Test
    void aRefusedReplyLeavesAClosedConversationClosed() {
        Business business = business();
        inboundAt(business, "now() - interval '25 hours'");
        admin.sql("update northstar.conversations set status = 'closed' where id = :id")
                .param("id", business.conversation())
                .update();

        assertThat(reply(business.staffUser(), business.conversation(), text("Too late"), key()))
                .hasStatus(409);

        assertThat(admin.sql("select status from northstar.conversations where id = :id")
                        .param("id", business.conversation())
                        .query(String.class)
                        .single())
                .isEqualTo("closed");
    }

    // Context and logs

    @Test
    void P10_onlyAdminsAndStaffQueueAStaffReply() {
        Business business = business();
        OutboundContent hello = new OutboundContent.Text("Hello");

        assertThatThrownBy(() -> messages.queueStaffReply(business.conversation(), hello, key()))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> SystemScope.call(
                        business.tenant(), () -> messages.queueStaffReply(business.conversation(), hello, key())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admins and staff");
        assertThat(messageCount(business)).isZero();
    }

    @Test
    void logsCarryIdsAndCodesButNeverTheTextOrTheKey(CapturedOutput output) {
        Business business = business();
        String key = "secret-request-key-" + UUID.randomUUID();
        UUID messageId = id(reply(business.staffUser(), business.conversation(), text("Private reply text"), key));
        send(business, messageId);
        reply(business.staffUser(), business.conversation(), text("Another private text"), key);

        assertThat(output.getAll())
                .contains("whatsapp.send.staff_reply_queued message_id=" + messageId)
                .doesNotContain("Private reply text", "Another private text", key, business.phone());
    }

    // Helpers.

    private record Business(
            UUID tenant,
            String number,
            UUID contact,
            String phone,
            UUID conversation,
            UUID adminUser,
            UUID adminMembership,
            UUID staffUser,
            UUID staffMembership,
            UUID otherMembership) {}

    /** A connected business with an admin and two staff members; the contact's conversation is the first staff's. */
    private Business business() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(tenant, staffUser, "staff", "active");
        UUID otherMembership = data.membership(tenant, data.user(), "staff", "active");
        String number = data.whatsAppNumber(tenant);
        String phone = "+91" + (9_000_000_000L + ThreadLocalRandom.current().nextLong(999_999_999L));
        UUID contact = data.contact(tenant, phone, null);
        UUID conversation = data.conversation(tenant, contact, number, staffMembership);
        connect(tenant);
        inboundAt(conversation, "now() - interval '1 hour'");
        return new Business(
                tenant,
                number,
                contact,
                phone,
                conversation,
                owner.userId(),
                owner.membershipId(),
                staffUser,
                staffMembership,
                otherMembership);
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

    /** An approved (or other status) template "Hello {{1}}, see you soon", with its variables read as the sync would. */
    private UUID template(UUID tenant, String status) {
        return admin.sql("""
                        insert into northstar.message_templates
                          (tenant_id, name, language, category, body, variables, status)
                        values (:t, :name, 'en_US', 'UTILITY', 'Hello {{1}}, see you soon',
                                '{"format": "positional", "names": ["1"]}', :status)
                        returning id""")
                .param("t", tenant)
                .param("name", "reply_" + UUID.randomUUID().toString().replace("-", ""))
                .param("status", status)
                .query(UUID.class)
                .single();
    }

    private void inboundAt(Business business, String sqlTime) {
        inboundAt(business.conversation(), sqlTime);
    }

    /** The conversation's last inbound message at a SQL time, as an operator sets it. */
    private void inboundAt(UUID conversation, String sqlTime) {
        admin.sql("update northstar.conversations set last_inbound_at = " + sqlTime + " where id = :id")
                .param("id", conversation)
                .update();
    }

    private MvcTestResult reply(UUID user, UUID conversation, String json, String idempotencyKey) {
        var request = mvc.post()
                .uri("/api/v1/conversations/" + conversation + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, "staff-reply-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey);
        return request.exchange();
    }

    private static String text(String text) {
        return JSON.createObjectNode().put("type", "text").put("text", text).toString();
    }

    private static String templateReply(UUID template, String... values) {
        var node = JSON.createObjectNode().put("type", "template").put("templateId", template.toString());
        var array = node.putArray("values");
        for (String value : values) array.add(value);
        return node.toString();
    }

    /** Runs the send as the worker does, with no web request bound to the thread. */
    private void send(Business business, UUID messageId) {
        UUID action = admin.sql("select id from northstar.actions where payload ->> 'messageId' = :m")
                .param("m", messageId.toString())
                .query(UUID.class)
                .single();
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            executeAction.run(new ExecuteAction(business.tenant(), action));
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    /** type|status|recipient of the message's send action */
    private String action(UUID messageId) {
        return admin.sql("""
                        select type || '|' || status || '|' || recipient_contact_id from northstar.actions
                        where payload ->> 'messageId' = :m""")
                .param("m", messageId.toString())
                .query(String.class)
                .single();
    }

    private String messageStatus(UUID messageId) {
        return admin.sql("select status from northstar.messages where id = :id")
                .param("id", messageId)
                .query(String.class)
                .single();
    }

    private long messageCount(Business business) {
        return admin.sql("select count(*) from northstar.messages where tenant_id = :t and direction = 'out'")
                .param("t", business.tenant())
                .query(Long.class)
                .single();
    }

    private long actionCount(Business business) {
        return admin.sql("select count(*) from northstar.actions where tenant_id = :t and type = 'whatsapp.send'")
                .param("t", business.tenant())
                .query(Long.class)
                .single();
    }

    private static JsonNode json(MvcTestResult result) {
        return JSON.readTree(body(result));
    }

    private static UUID id(MvcTestResult result) {
        return UUID.fromString(json(result).get("id").stringValue());
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }
}
