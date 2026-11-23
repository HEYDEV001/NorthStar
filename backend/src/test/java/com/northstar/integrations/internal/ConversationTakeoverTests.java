package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.contacts.AutomationPausedException;
import com.northstar.contacts.ConversationControl;
import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Taking a conversation over and handing it back (step 2.9c) through the real login path, against Postgres with RLS
 * on: admins take over any conversation and staff their own (P9, P11); taking over records who and when, pauses
 * automation and cancels the conversation's queued automated sends, and nothing else; queueing refuses automated sends
 * meanwhile; only a hand back resumes automation; each change is audited and, with an open lead the member can see,
 * recorded as {@code staff.action}; and the locks never deadlock with an opt-out or a claim.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, WhatsAppSendTests.WorkerCredentials.class})
@ExtendWith(OutputCaptureExtension.class)
class ConversationTakeoverTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final WhatsAppMessages messages;
    private final ConversationControl control;
    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler inboundJob;
    private final ExecuteActionHandler executeAction;
    private final FakeWhatsAppSender whatsApp;
    private final CredentialStore credentials;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    ConversationTakeoverTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired WhatsAppMessages messages,
            @Autowired ConversationControl control,
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler inboundJob,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeWhatsAppSender whatsApp,
            @Autowired CredentialStore credentials,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.messages = messages;
        this.control = control;
        this.intake = intake;
        this.inboundJob = inboundJob;
        this.executeAction = executeAction;
        this.whatsApp = whatsApp;
        this.credentials = credentials;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    // Who may take over, and what it records

    @Test
    void anAdminTakesOverAnyConversationAndItRecordsWhoAndWhen() {
        Business business = business();

        MvcTestResult result = takeOver(business.adminUser(), business.conversation());

        assertThat(result).hasStatusOk();
        JsonNode view = json(result);
        assertThat(view.get("automationPaused").booleanValue()).isTrue();
        assertThat(view.get("takenOverByMembershipId").stringValue())
                .isEqualTo(business.adminMembership().toString());
        assertThat(view.get("takenOverAt").isNull()).isFalse();
        assertThat(state(business.conversation())).isEqualTo("true|" + business.adminMembership() + "|true");
        assertThat(audit(business, "conversation.taken_over"))
                .containsExactly("conversation.taken_over|conversations|" + business.conversation() + "|"
                        + business.adminUser() + "|{\"to\": \"taken_over\", \"from\": \"automated\"}");
    }

    @Test
    void P11_staffTakeOverTheirOwnConversationsOnly() throws Exception {
        Business business = business();
        UUID colleagues = data.conversation(
                business.tenant(), data.contact(business.tenant()), business.number(), business.otherMembership());
        UUID unassigned =
                data.conversation(business.tenant(), data.contact(business.tenant()), business.number(), null);

        assertThat(json(takeOver(business.staffUser(), business.conversation()))
                        .get("takenOverByMembershipId")
                        .stringValue())
                .isEqualTo(business.staffMembership().toString());
        String missing = body(takeOver(business.staffUser(), UUID.randomUUID()));
        for (UUID hidden : List.of(colleagues, unassigned)) {
            for (MvcTestResult refused :
                    List.of(takeOver(business.staffUser(), hidden), handBack(business.staffUser(), hidden))) {
                assertThat(refused).hasStatus(404);
                assertThat(body(refused))
                        .as("the same 404 as a missing conversation")
                        .isEqualTo(missing);
            }
            assertThat(state(hidden)).as("unchanged").isEqualTo("false||false");
        }
    }

    @Test
    void P9_anotherTenantsConversationIsTheSame404AsAMissingOne() throws Exception {
        Business ours = business();
        Business theirs = business();

        for (String path : List.of("take-over", "hand-back")) {
            MvcTestResult foreign = post(ours.adminUser(), theirs.conversation(), path);
            MvcTestResult missing = post(ours.adminUser(), UUID.randomUUID(), path);
            assertThat(foreign).hasStatus(404);
            assertThat(body(foreign)).isEqualTo(body(missing));
        }
        assertThat(state(theirs.conversation())).isEqualTo("false||false");
    }

    // What taking over cancels

    @Test
    void takingOverCancelsTheConversationsQueuedAutomatedSendsAndNothingElse(CapturedOutput output) {
        Business business = business();
        UUID template = template(business.tenant());
        WhatsAppMessages.Queued pending = queueText(business, OutboundMessages.Sender.SYSTEM);
        WhatsAppMessages.Queued fromAi = queueText(business, OutboundMessages.Sender.AI);
        WhatsAppMessages.Queued waitingForReview = queueTemplate(business, template);
        WhatsAppMessages.Queued authPaused = queueText(business, OutboundMessages.Sender.SYSTEM);
        WhatsAppMessages.Queued blocked = queueTemplate(business, template);
        WhatsAppMessages.Queued inFlight = queueText(business, OutboundMessages.Sender.SYSTEM);
        setAction(waitingForReview, "status = 'needs_review', next_attempt_at = null");
        setAction(
                authPaused,
                "status = 'paused', next_attempt_at = null, last_error_class = 'auth',"
                        + " last_error_code = 'integration_not_connected'");
        setAction(blocked, "status = 'paused', last_error_class = 'blocked', last_error_code = 'template_paused'");
        setAction(
                inFlight,
                "status = 'processing', next_attempt_at = null, attempts = 1, last_attempt_at = now(),"
                        + " lease_expires_at = now() + interval '5 minutes'");
        UUID staffReply = id(reply(business, "A person's reply"));
        Business other = sameTenant(business);
        WhatsAppMessages.Queued elsewhere = queueText(other, OutboundMessages.Sender.SYSTEM);

        assertThat(takeOver(business.staffUser(), business.conversation())).hasStatusOk();

        for (WhatsAppMessages.Queued cancelled : List.of(pending, fromAi, waitingForReview, authPaused, blocked)) {
            assertThat(sendState(cancelled.messageId()))
                    .isEqualTo("cancelled|conversation_taken_over|failed|conversation_taken_over");
        }
        assertThat(sendState(inFlight.messageId()))
                .as("an attempt in flight is left to finish")
                .isEqualTo("processing||queued|");
        assertThat(sendState(staffReply)).as("staff replies are unaffected").isEqualTo("pending||queued|");
        assertThat(sendState(elsewhere.messageId()))
                .as("another conversation's")
                .isEqualTo("pending||queued|");
        assertThat(output.getAll())
                .contains("conversation.taken_over conversation_id=" + business.conversation() + " cancelled_sends=5");
    }

    @Test
    void aSecondTakeOverChangesNothingWhoeverAsks() {
        Business business = business();
        JsonNode first = json(takeOver(business.staffUser(), business.conversation()));

        JsonNode second = json(takeOver(business.adminUser(), business.conversation()));

        assertThat(second.get("takenOverByMembershipId").stringValue())
                .isEqualTo(business.staffMembership().toString());
        assertThat(second.get("takenOverAt").stringValue())
                .isEqualTo(first.get("takenOverAt").stringValue());
        assertThat(audit(business, "conversation.taken_over")).hasSize(1);
    }

    // Handing back

    @Test
    void handingBackResumesAutomationButBringsNothingBack() {
        Business business = business();
        WhatsAppMessages.Queued cancelled = queueText(business, OutboundMessages.Sender.SYSTEM);
        takeOver(business.staffUser(), business.conversation());

        JsonNode view = json(handBack(business.staffUser(), business.conversation()));

        assertThat(view.get("automationPaused").booleanValue()).isFalse();
        assertThat(view.get("takenOverByMembershipId").isNull()).isTrue();
        assertThat(view.get("takenOverAt").isNull()).isTrue();
        assertThat(state(business.conversation())).isEqualTo("false||false");
        assertThat(sendState(cancelled.messageId()))
                .as("still cancelled")
                .isEqualTo("cancelled|conversation_taken_over|failed|conversation_taken_over");
        assertThat(json(handBack(business.staffUser(), business.conversation()))
                        .get("automationPaused")
                        .booleanValue())
                .isFalse();
        assertThat(audit(business, "conversation.handed_back"))
                .as("once, by the member, from taken over to automated")
                .containsExactly("conversation.handed_back|conversations|" + business.conversation() + "|"
                        + business.staffUser() + "|{\"to\": \"automated\", \"from\": \"taken_over\"}");
        assertThat(queueText(business, OutboundMessages.Sender.SYSTEM).action().status())
                .as("automated sends are queued again")
                .isEqualTo("pending");
    }

    @Test
    void onlyAHandBackEndsATakeOverAndStaffKeepReplying() {
        Business business = business();
        takeOver(business.staffUser(), business.conversation());

        storeInbound(business, "Are you there?");
        UUID reply = id(reply(business, "Yes, I'm here"));
        send(business, reply);

        assertThat(state(business.conversation()))
                .as("still taken over")
                .isEqualTo("true|" + business.staffMembership() + "|true");
        assertThat(whatsApp.sent(reply))
                .as("a staff reply is sent while taken over")
                .hasSize(1);
    }

    @Test
    void automatedSendsAreRefusedAtQueueTimeWhileTakenOver() {
        Business business = business();
        String earlierKey = "test:" + UUID.randomUUID();
        WhatsAppMessages.Queued earlier = SystemScope.call(
                business.tenant(),
                () -> messages.queueText(
                        business.conversation(), "Queued before", OutboundMessages.Sender.SYSTEM, earlierKey));
        takeOver(business.staffUser(), business.conversation());
        long before = outboundCount(business);

        for (OutboundMessages.Sender sender : OutboundMessages.Sender.values()) {
            assertThatThrownBy(() -> queueText(business, sender))
                    .as(sender.name())
                    .isInstanceOfSatisfying(
                            AutomationPausedException.class,
                            refused -> assertThat(refused.conversationId()).isEqualTo(business.conversation()));
        }
        assertThatThrownBy(() -> queueTemplate(business, template(business.tenant())))
                .isInstanceOf(AutomationPausedException.class);
        assertThat(outboundCount(business)).as("nothing stored").isEqualTo(before);

        WhatsAppMessages.Queued repeat = SystemScope.call(
                business.tenant(),
                () -> messages.queueText(
                        business.conversation(), "Queued before", OutboundMessages.Sender.SYSTEM, earlierKey));
        assertThat(repeat.messageId())
                .as("a repeat of a send queued before returns it")
                .isEqualTo(earlier.messageId());
        assertThat(repeat.created()).isFalse();
    }

    // The staff.action event

    @Test
    void aStaffActionEventGoesOnTheOpenLeadTheMemberCanSeeAndEachTakeOverHasItsOwn() {
        Business business = business();
        UUID lead = data.lead(
                business.tenant(),
                data.workflowVersion(business.tenant()),
                business.contact(),
                business.conversation(),
                business.staffMembership());
        queueText(business, OutboundMessages.Sender.SYSTEM);

        Instant first = Instant.parse(json(takeOver(business.staffUser(), business.conversation()))
                .get("takenOverAt")
                .stringValue());
        handBack(business.staffUser(), business.conversation());
        Instant second = Instant.parse(json(takeOver(business.staffUser(), business.conversation()))
                .get("takenOverAt")
                .stringValue());
        takeOver(business.staffUser(), business.conversation()); // already taken over: nothing recorded

        List<JsonNode> recorded = staffActions(business);
        assertThat(recorded)
                .extracting(event -> event.get("dedupe_key").stringValue())
                .containsExactly(
                        "staff.action:take_over:" + business.conversation() + ":" + micros(first),
                        "staff.action:hand_back:" + business.conversation() + ":" + micros(first),
                        "staff.action:take_over:" + business.conversation() + ":" + micros(second));
        assertThat(recorded)
                .allSatisfy(
                        event -> assertThat(event.get("lead_id").stringValue()).isEqualTo(lead.toString()));
        JsonNode payload = recorded.getFirst().get("payload");
        assertThat(payload.propertyNames())
                .containsExactlyInAnyOrder(
                        "action", "reason", "conversation_id", "contact_id", "membership_id", "cancelled_sends", "at");
        assertThat(payload.get("action").stringValue()).isEqualTo("take_over");
        assertThat(payload.get("reason").stringValue()).isEqualTo("conversation_taken_over");
        assertThat(payload.get("membership_id").stringValue())
                .isEqualTo(business.staffMembership().toString());
        assertThat(payload.get("cancelled_sends").intValue()).isEqualTo(1);
        assertThat(recorded.get(1).get("payload").get("reason").stringValue()).isEqualTo("conversation_handed_back");
    }

    @Test
    void withoutAnOpenLeadTheMemberCanSeeOnlyTheAuditEntryIsWritten() {
        Business noLead = business();
        takeOver(noLead.adminUser(), noLead.conversation());
        assertThat(staffActions(noLead)).isEmpty();
        assertThat(audit(noLead, "conversation.taken_over")).hasSize(1);

        Business closedLead = business();
        data.closeLead(
                data.lead(
                        closedLead.tenant(),
                        data.workflowVersion(closedLead.tenant()),
                        closedLead.contact(),
                        closedLead.conversation(),
                        closedLead.staffMembership()),
                "lost");
        takeOver(closedLead.staffUser(), closedLead.conversation());
        assertThat(staffActions(closedLead)).as("a closed lead is not acted on").isEmpty();
        assertThat(audit(closedLead, "conversation.taken_over")).hasSize(1);
    }

    // Locks

    @Test
    void aTakeOverDuringAnOptOutWaitsForItAndNeverDeadlocks() throws Exception {
        for (int round = 0; round < 5; round++) {
            Business business = business();
            WhatsAppMessages.Queued queued = queueText(business, OutboundMessages.Sender.SYSTEM);
            AtomicReference<Future<MvcTestResult>> taking = new AtomicReference<>();

            optOutInProgress(business.contact(), jdbc -> {
                taking.set(threads.submit(() -> takeOver(business.staffUser(), business.conversation())));
                awaitWaiting("%northstar.contacts%for share%");
                jdbc.sql("select id from northstar.conversations where id = :id for update")
                        .param("id", business.conversation())
                        .query(UUID.class)
                        .single();
                return jdbc.sql("""
                                update northstar.actions
                                set status = 'cancelled', cancel_reason = 'contact_opted_out', next_attempt_at = null
                                where recipient_contact_id = :c and status in ('pending', 'needs_review', 'paused')""").param("c", business.contact()).update();
            });

            assertThat(taking.get().get(30, TimeUnit.SECONDS))
                    .as("round " + round)
                    .hasStatusOk();
            assertThat(sendState(queued.messageId()))
                    .as("the opt-out's cancellation stands")
                    .startsWith("cancelled|contact_opted_out|");
        }
    }

    @Test
    void aTakeOverDuringAClaimWaitsForItAndLeavesTheAttemptToFinish() throws Exception {
        for (int round = 0; round < 5; round++) {
            Business business = business();
            WhatsAppMessages.Queued queued = queueText(business, OutboundMessages.Sender.SYSTEM);
            UUID action = queued.action().id();
            AtomicReference<Future<MvcTestResult>> taking = new AtomicReference<>();

            asOperator(jdbc -> {
                // A claim's locks, in its order: the recipient (shared), then the action.
                jdbc.sql("select id from northstar.contacts where id = :id for share")
                        .param("id", business.contact())
                        .query(UUID.class)
                        .single();
                jdbc.sql("select id from northstar.actions where id = :id for update")
                        .param("id", action)
                        .query(UUID.class)
                        .single();
                taking.set(threads.submit(() -> takeOver(business.staffUser(), business.conversation())));
                awaitWaiting("%northstar.actions%for update%");
                return jdbc.sql("""
                                update northstar.actions
                                set status = 'processing', next_attempt_at = null, attempts = 1, last_attempt_at = now(),
                                    lease_expires_at = now() + interval '5 minutes'
                                where id = :id""").param("id", action).update();
            });

            assertThat(taking.get().get(30, TimeUnit.SECONDS))
                    .as("round " + round)
                    .hasStatusOk();
            assertThat(sendState(queued.messageId()))
                    .as("claimed first, so left to its attempt")
                    .isEqualTo("processing||queued|");
        }
    }

    // Context and logs

    @Test
    void P10_onlyAdminsAndStaffTakeOverOrHandBack() {
        Business business = business();

        assertThatThrownBy(() -> transactions.execute(status -> control.takeOver(business.conversation())))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> SystemScope.call(
                        business.tenant(),
                        () -> transactions.execute(status -> control.takeOver(business.conversation()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admins and staff");
        assertThatThrownBy(() -> SystemScope.call(
                        business.tenant(),
                        () -> transactions.execute(status -> control.handBack(business.conversation()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state(business.conversation())).isEqualTo("false||false");
    }

    @Test
    void logsCarryIdsCodesAndCountsOnly(CapturedOutput output) {
        Business business = business();
        queueText(business, OutboundMessages.Sender.SYSTEM);
        takeOver(business.staffUser(), business.conversation());
        takeOver(business.staffUser(), business.conversation());
        handBack(business.staffUser(), business.conversation());
        handBack(business.staffUser(), business.conversation());

        assertThat(output.getAll())
                .contains("conversation.taken_over conversation_id=" + business.conversation())
                .contains("conversation.take_over_unchanged conversation_id=" + business.conversation())
                .contains("conversation.handed_back conversation_id=" + business.conversation())
                .contains("conversation.hand_back_unchanged conversation_id=" + business.conversation())
                .contains("reason=conversation_taken_over at=dependency")
                .doesNotContain("Automated text", business.phone());
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

    /**
     * A connected business with an admin and two staff members; the contact's conversation, inside its window, is the
     * first staff member's.
     */
    private Business business() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(tenant, staffUser, "staff", "active");
        UUID otherMembership = data.membership(tenant, data.user(), "staff", "active");
        String number = data.whatsAppNumber(tenant);
        connect(tenant);
        return withConversation(new Business(
                tenant,
                number,
                null,
                null,
                null,
                owner.userId(),
                owner.membershipId(),
                staffUser,
                staffMembership,
                otherMembership));
    }

    /** Another contact's conversation in the same business, assigned to the same staff member. */
    private Business sameTenant(Business business) {
        return withConversation(business);
    }

    private Business withConversation(Business business) {
        String phone = "+91" + (9_000_000_000L + ThreadLocalRandom.current().nextLong(999_999_999L));
        UUID contact = data.contact(business.tenant(), phone, null);
        UUID conversation =
                data.conversation(business.tenant(), contact, business.number(), business.staffMembership());
        admin.sql("update northstar.conversations set last_inbound_at = now() - interval '1 hour' where id = :id")
                .param("id", conversation)
                .update();
        return new Business(
                business.tenant(),
                business.number(),
                contact,
                phone,
                conversation,
                business.adminUser(),
                business.adminMembership(),
                business.staffUser(),
                business.staffMembership(),
                business.otherMembership());
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
                        values (:t, :name, 'en_US', 'UTILITY', 'Hello {{1}}', '{"format": "positional",
                                "names": ["1"]}', 'approved')
                        returning id""")
                .param("t", tenant)
                .param("name", "auto_" + UUID.randomUUID().toString().replace("-", ""))
                .query(UUID.class)
                .single();
    }

    private WhatsAppMessages.Queued queueText(Business business, OutboundMessages.Sender sender) {
        return SystemScope.call(
                business.tenant(),
                () -> messages.queueText(
                        business.conversation(), "Automated text", sender, "test:" + UUID.randomUUID()));
    }

    private WhatsAppMessages.Queued queueTemplate(Business business, UUID template) {
        return SystemScope.call(
                business.tenant(),
                () -> messages.queueTemplate(
                        business.conversation(),
                        template,
                        TemplateParameters.positional("Asha"),
                        OutboundMessages.Sender.SYSTEM,
                        "test:" + UUID.randomUUID()));
    }

    private void setAction(WhatsAppMessages.Queued queued, String assignments) {
        admin.sql("update northstar.actions set " + assignments + " where id = :id")
                .param("id", queued.action().id())
                .update();
    }

    private MvcTestResult takeOver(UUID user, UUID conversation) {
        return post(user, conversation, "take-over");
    }

    private MvcTestResult handBack(UUID user, UUID conversation) {
        return post(user, conversation, "hand-back");
    }

    private MvcTestResult post(UUID user, UUID conversation, String path) {
        return mvc.post()
                .uri("/api/v1/conversations/" + conversation + "/" + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, "take-over-test")
                .exchange();
    }

    private MvcTestResult reply(Business business, String text) {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/conversations/" + business.conversation() + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(business.staffUser()))
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.createObjectNode()
                        .put("type", "text")
                        .put("text", text)
                        .toString())
                .exchange();
        assertThat(result).hasStatus(201);
        return result;
    }

    private void storeInbound(Business business, String text) {
        WhatsAppPayloads.Inbound message = WhatsAppPayloads.inbound(business.number())
                .from(business.phone().substring(1))
                .sentAt(Instant.now().truncatedTo(ChronoUnit.SECONDS))
                .text(text);
        intake.receive(message.json().getBytes(StandardCharsets.UTF_8));
        UUID row = admin.sql(
                        "select id from northstar.webhook_events where provider = 'whatsapp' and provider_event_id = :e")
                .param("e", "message:" + message.wamid())
                .query(UUID.class)
                .single();
        asWorker(() -> inboundJob.run(new ProcessWhatsAppWebhook(business.tenant(), row)));
    }

    private void send(Business business, UUID messageId) {
        UUID action = admin.sql("select id from northstar.actions where payload ->> 'messageId' = :m")
                .param("m", messageId.toString())
                .query(UUID.class)
                .single();
        asWorker(() -> executeAction.run(new ExecuteAction(business.tenant(), action)));
    }

    private static void asWorker(Runnable work) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            work.run();
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    /** One transaction as the database's owner, committed once the steps return. Lock waits give up after 3 seconds. */
    private <T> T asOperator(Function<JdbcClient, T> steps) {
        JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
        return new TransactionTemplate(new DataSourceTransactionManager(postgres.dataSource())).execute(status -> {
            jdbc.sql("set local lock_timeout = '3s'").update();
            return steps.apply(jdbc);
        });
    }

    /** An opt-out as the inbound job records it (steps 2.4b and 2.5): the contact locked for update first. */
    private <T> T optOutInProgress(UUID contact, Function<JdbcClient, T> steps) {
        return asOperator(jdbc -> {
            jdbc.sql("select id from northstar.contacts where id = :id for update")
                    .param("id", contact)
                    .query(UUID.class)
                    .single();
            jdbc.sql("""
                            update northstar.contacts set consent_state = 'opted_out', opt_out_at = now(),
                              last_opt_out_at = now() where id = :id""").param("id", contact).update();
            return steps.apply(jdbc);
        });
    }

    /** Until another session waits for a row lock in a query like this. */
    private void awaitWaiting(String queryLike) {
        await().atMost(WAIT)
                .until(() ->
                        admin.sql("""
                                select count(*) from pg_stat_activity
                                where wait_event_type = 'Lock' and query like :q""").param("q", queryLike).query(Long.class).single() == 1);
    }

    /** automation_paused|taken_over_by_membership_id|taken_over_at is set */
    private String state(UUID conversation) {
        return admin.sql("""
                        select automation_paused || '|' || coalesce(taken_over_by_membership_id::text, '') || '|'
                               || (taken_over_at is not null)
                        from northstar.conversations where id = :id""").param("id", conversation).query(String.class).single();
    }

    /** action status|cancel_reason|message status|error_code, for a message's send */
    private String sendState(UUID messageId) {
        return admin.sql("""
                        select a.status || '|' || coalesce(a.cancel_reason, '') || '|' || m.status || '|'
                               || coalesce(m.error_code, '')
                        from northstar.messages m
                        join northstar.actions a on a.payload ->> 'messageId' = m.id::text and a.tenant_id = m.tenant_id
                        where m.id = :id""").param("id", messageId).query(String.class).single();
    }

    /** action|target_table|target_id|actor_user_id|metadata, oldest first */
    private List<String> audit(Business business, String action) {
        return admin.sql("""
                        select action || '|' || target_table || '|' || target_id || '|' || actor_user_id || '|'
                               || metadata::text
                        from northstar.audit_log where tenant_id = :t and action = :a order by created_at""")
                .param("t", business.tenant())
                .param("a", action)
                .query(String.class)
                .list();
    }

    private List<JsonNode> staffActions(Business business) {
        return admin.sql("""
                        select json_build_object('dedupe_key', dedupe_key, 'lead_id', lead_id, 'payload', payload)::text
                        from northstar.events where tenant_id = :t and type = 'staff.action' order by received_at, id""").param("t", business.tenant()).query(String.class).list().stream()
                .map(JSON::readTree)
                .toList();
    }

    private long outboundCount(Business business) {
        return admin.sql("select count(*) from northstar.messages where tenant_id = :t and direction = 'out'")
                .param("t", business.tenant())
                .query(Long.class)
                .single();
    }

    private static long micros(Instant at) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, at);
    }

    private static JsonNode json(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return JSON.readTree(body(result));
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
}
