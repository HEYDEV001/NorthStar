package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.contacts.ContactConsent;
import com.northstar.contacts.InboundMessageType;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.fakes.WhatsAppPayloads.Inbound;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.Actions;
import com.northstar.workflow.EngineEvents;
import com.northstar.workflow.NewAction;
import com.northstar.workflow.OutboundCancellation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-out on inbound messages (step 2.5, W32; workflow-spec.md section 13 Opt-out, integration-specs.md section 2)
 * against real Postgres with RLS, through the real intake (step 2.2) and the inbound job exactly as the worker runs it:
 * a message whose whole text is a platform or tenant keyword opts its sender out, cancels every action that could still
 * message them, leaves the stage alone and opens no lead, and is still stored in the conversation, all in the job's one
 * transaction. The worker is off in this context, so queued jobs are only stored.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class OptOutTests {

    /** FakeActionHandlers' type that sends something to a contact, and its type that messages nobody outside. */
    private static final String CONTACT_TYPE = "test.fake.contact";

    private static final String NO_RECIPIENT_TYPE = "test.fake";

    /** The fake customer of {@link WhatsAppPayloads}, as WhatsApp sends it and as contacts store it. */
    private static final String CUSTOMER_WA_ID = "910000000001";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The message of the injected event-insert failure, which must never reach a log. */
    private static final String FAILURE_MARKER = "injected-failure-" + UUID.randomUUID();

    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler handler;
    private final Actions actions;
    private final ContactConsent consent;
    private final OutboundCancellation cancellation;
    private final EngineEvents engineEvents;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;

    OptOutTests(
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler handler,
            @Autowired Actions actions,
            @Autowired ContactConsent consent,
            @Autowired OutboundCancellation cancellation,
            @Autowired EngineEvents engineEvents,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.intake = intake;
        this.handler = handler;
        this.actions = actions;
        this.consent = consent;
        this.cancellation = cancellation;
        this.engineEvents = engineEvents;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    // What an opt-out cancels

    @Test
    void W32_stopCancelsEveryPendingNeedsReviewAndPausedActionForTheContact(CapturedOutput output) {
        Business business = business();
        Customer customer = customer(business, CUSTOMER_WA_ID);
        Map<String, UUID> cancellable = new LinkedHashMap<>();
        cancellable.put("pending and due", contactAction(business, customer, false));
        cancellable.put("pending for later", later(contactAction(business, customer, false)));
        cancellable.put("waiting for a retry", waitingForRetry(contactAction(business, customer, false)));
        cancellable.put("needs review", contactAction(business, customer, true));
        cancellable.put("paused", paused(contactAction(business, customer, false)));
        Map<String, UUID> kept = new LinkedHashMap<>();
        kept.put("processing", processing(contactAction(business, customer, false)));
        kept.put("succeeded", succeeded(contactAction(business, customer, false)));
        kept.put("failed", failed(contactAction(business, customer, false)));
        Map<String, String> keptBefore = new LinkedHashMap<>();
        kept.forEach((name, id) -> keptBefore.put(name, actionState(id)));

        UUID row = store(inbound(business, CUSTOMER_WA_ID).text("STOP"));
        run(business.tenant(), row);

        cancellable.forEach((name, id) ->
                assertThat(actionState(id)).as(name).isEqualTo("cancelled|contact_opted_out|no next attempt|no lease"));
        kept.forEach((name, id) ->
                assertThat(actionState(id)).as(name + " is left as it was").isEqualTo(keptBefore.get(name)));
        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isNull();
        assertThat(output.getAll())
                .contains("webhook.opt_out provider=whatsapp webhook_event_id=" + row)
                .contains("contact_id=" + customer.contact() + " lead_id=" + customer.lead()
                        + " lead=joined first_opt_out=true cancelled_actions=5");
    }

    @Test
    void W32_actionsForOtherContactsStaffAndNoRecipientAreUntouched(CapturedOutput output) {
        Business business = business();
        Customer customer = customer(business, CUSTOMER_WA_ID);
        Customer other = customer(business, randomWaId());
        UUID mine = contactAction(business, customer, false);
        UUID staffAboutMyLead = noRecipientAction(business, customer.lead());
        UUID otherContacts = contactAction(business, other, false);
        UUID noLeadNoRecipient = noRecipientAction(business, null);

        UUID row = store(inbound(business, CUSTOMER_WA_ID).text("stop"));
        run(business.tenant(), row);

        assertThat(actionState(mine)).startsWith("cancelled|contact_opted_out");
        assertThat(actionState(staffAboutMyLead)).isEqualTo("pending||next attempt|lead");
        assertThat(actionState(otherContacts)).isEqualTo("pending||next attempt|lead");
        assertThat(actionState(noLeadNoRecipient)).isEqualTo("pending||next attempt|no lead");
        assertThat(consentState(other.contact())).isEqualTo("unknown");
        assertThat(output.getAll()).contains("first_opt_out=true cancelled_actions=1");
    }

    // Stage, message and lead

    @Test
    void W32_theStageDoesNotChangeAndTheMessageIsStoredInTheConversation() {
        Business business = business();
        Customer customer = customer(business, CUSTOMER_WA_ID);
        // In Needs Human from Quoted, so both the stage and the stage it was entered from are set (step 3.3's check).
        admin.sql("update northstar.leads set previous_stage = 'quoted', stage = 'needs_human' where id = :id")
                .param("id", customer.lead())
                .update();
        String leadBefore = leadState(customer.lead());
        Instant sentAt = Instant.parse("2026-11-21T08:15:00Z");
        Inbound stop = inbound(business, CUSTOMER_WA_ID).sentAt(sentAt).text("Stop!");

        UUID row = store(stop);
        run(business.tenant(), row);

        assertThat(leadState(customer.lead())).as("stage unchanged (W32)").isEqualTo(leadBefore);
        assertThat(leadCount(business.tenant(), customer.contact())).isOne();
        assertThat(consentState(customer.contact())).isEqualTo("opted_out");
        assertThat(optOutAt(customer.contact()).toInstant()).isEqualTo(sentAt);
        assertThat(admin.sql("""
                        select direction || '|' || sender_type || '|' || type || '|' || body
                        from northstar.messages where conversation_id = :c and provider_message_id = :w""")
                        .param("c", customer.conversation())
                        .param("w", stop.wamid())
                        .query(String.class)
                        .single())
                .as("stored in the conversation as usual")
                .isEqualTo("in|contact|text|Stop!");
        assertThat(lastInboundAt(customer.conversation()).toInstant()).isEqualTo(sentAt);

        EventRow event = event(business.tenant(), "optout.received:" + stop.wamid());
        assertThat(event.type()).isEqualTo("optout.received");
        assertThat(event.leadId()).isEqualTo(customer.lead());
        assertThat(event.processed()).as("the engine (Phase 3) processes it").isFalse();
        assertThat(eventCount(business.tenant(), "message.received:" + stop.wamid()))
                .as("an opt-out records optout.received only")
                .isZero();
        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isNull();
    }

    @Test
    void W32_optOutAtIsSetOnceAndNeverMovedAndLaterMessagesAreStillStored(CapturedOutput output) {
        Business business = business();
        Customer customer = customer(business, CUSTOMER_WA_ID);
        Instant first = Instant.parse("2026-11-21T08:00:00Z");
        run(
                business.tenant(),
                store(inbound(business, CUSTOMER_WA_ID).sentAt(first).text("STOP")));
        OffsetDateTime optedOutAt = optOutAt(customer.contact());
        assertThat(optedOutAt.toInstant()).isEqualTo(first);

        UUID again = store(
                inbound(business, CUSTOMER_WA_ID).sentAt(first.plusSeconds(600)).text("Unsubscribe"));
        run(business.tenant(), again);
        UUID older = store(inbound(business, CUSTOMER_WA_ID)
                .sentAt(first.minusSeconds(600))
                .text("opt out"));
        run(business.tenant(), older);

        assertThat(optOutAt(customer.contact()))
                .as("never moved, by a later or a late-arriving earlier one")
                .isEqualTo(optedOutAt);
        assertThat(consentState(customer.contact())).isEqualTo("opted_out");
        assertThat(output.getAll())
                .contains("webhook.opt_out provider=whatsapp webhook_event_id=" + again)
                .contains("webhook.opt_out provider=whatsapp webhook_event_id=" + older);
        assertThat(logLine(output, again)).contains("first_opt_out=false");
        assertThat(logLine(output, older)).contains("first_opt_out=false");

        // A message that is not a keyword is processed as usual; the contact stays opted out.
        Inbound hello = inbound(business, CUSTOMER_WA_ID).text("Hello again, are you open on Sunday?");
        run(business.tenant(), store(hello));
        assertThat(event(business.tenant(), "message.received:" + hello.wamid()).leadId())
                .isEqualTo(customer.lead());
        assertThat(consentState(customer.contact())).isEqualTo("opted_out");
        assertThat(eventCountOfType(business.tenant(), "optout.received")).isEqualTo(3);
    }

    @Test
    void W32_anOptOutMessageNeverOpensALeadButJoinsAnOpenOne(CapturedOutput output) {
        // A contact with no lead.
        Business business = business();
        String newcomer = randomWaId();
        Inbound first = inbound(business, newcomer).text("STOP");
        UUID row = store(first);
        run(business.tenant(), row);
        UUID contact = contactId(business.tenant(), newcomer);
        assertThat(leadCount(business.tenant(), contact)).isZero();
        assertThat(consentState(contact)).isEqualTo("opted_out");
        assertThat(conversationId(business.tenant(), contact)).isNotNull();
        EventRow event = event(business.tenant(), "optout.received:" + first.wamid());
        assertThat(event.leadId()).isNull();
        assertThat(JSON.readTree(event.payload()).path("lead").stringValue()).isEqualTo("none");
        assertThat(logLine(output, row)).contains("lead_id=null lead=none");

        // A contact whose lead is closed (W2 would open a new one for any other message).
        Customer returning = customer(business, randomWaId());
        data.closeLead(returning.lead(), "lost");
        run(business.tenant(), store(inbound(business, returning.waId()).text("STOP")));
        assertThat(leadCount(business.tenant(), returning.contact())).isOne();

        // A tenant with no published workflow: processed in full, with no workflow code, as no lead was wanted.
        UUID unpublished = data.tenant();
        Business noWorkflow = new Business(unpublished, data.whatsAppNumber(unpublished), null);
        UUID noWorkflowRow = store(inbound(noWorkflow, randomWaId()).text("STOP"));
        run(unpublished, noWorkflowRow);
        assertThat(processed(noWorkflowRow)).isTrue();
        assertThat(error(noWorkflowRow)).isNull();
        assertThat(count("leads", unpublished)).isZero();

        // An open lead with no conversation linked yet (entered by hand) is joined and linked (W1).
        String byHand = randomWaId();
        UUID handContact = data.contact(business.tenant(), "+" + byHand, null);
        UUID handLead = data.lead(business.tenant(), business.version(), handContact, null, null);
        Inbound stop = inbound(business, byHand).text("unsubscribe");
        run(business.tenant(), store(stop));
        UUID handConversation = conversationId(business.tenant(), handContact);
        assertThat(admin.sql("select conversation_id from northstar.leads where id = :id")
                        .param("id", handLead)
                        .query(UUID.class)
                        .single())
                .isEqualTo(handConversation);
        assertThat(event(business.tenant(), "optout.received:" + stop.wamid()).leadId())
                .isEqualTo(handLead);
        assertThat(leadCount(business.tenant(), handContact)).isOne();
    }

    // What counts as an opt-out

    @Test
    void W32_onlyAWholeMessageMatchingAKeywordOptsOut() {
        Business business = business();
        List<String> optOuts = List.of(
                "STOP",
                "stop",
                "Stop!",
                "  Stop  ",
                "STOP\n",
                "ＳＴＯＰ",
                "st​op",
                "Unsubscribe.",
                "UNSUBSCRIBE!!",
                "opt out",
                "Opt-out",
                "OPT_OUT",
                "optout",
                "OptOut!",
                "Stop promotions",
                "stop   promotions.");
        List<String> notOptOuts = List.of(
                "Please don't stop the shoot",
                "stop 🛑",
                "stopp",
                "stop now",
                "don't stop",
                "s t o p",
                "unsubscribe me",
                "opt in",
                "?",
                "...",
                "Stop the music. Book us for Saturday");
        for (String text : optOuts) {
            assertThat(optsOut(business, InboundMessage.text(text))).as(text).isTrue();
        }
        for (String text : notOptOuts) {
            assertThat(optsOut(business, InboundMessage.text(text))).as(text).isFalse();
        }
    }

    @Test
    void W32_interactiveRepliesAreChecked() {
        Business business = business();
        assertThat(optsOut(
                        business,
                        new InboundMessage(
                                "interactive",
                                "{\"type\":\"button_reply\",\"button_reply\":{\"id\":\"b1\",\"title\":\"Stop\"}}")))
                .isTrue();
        assertThat(optsOut(
                        business,
                        new InboundMessage(
                                "interactive",
                                "{\"type\":\"list_reply\",\"list_reply\":{\"id\":\"r1\",\"title\":\"Unsubscribe\"}}")))
                .isTrue();
        assertThat(optsOut(
                        business,
                        new InboundMessage(
                                "button", "{\"payload\":\"marketing-opt-out\",\"text\":\"Stop promotions\"}")))
                .as("Meta's marketing opt-out button, a full opt-out for now")
                .isTrue();
        assertThat(
                        optsOut(
                                business,
                                new InboundMessage(
                                        "interactive",
                                        "{\"type\":\"button_reply\",\"button_reply\":{\"id\":\"quote:1\",\"title\":\"Accept\"}}")))
                .isFalse();
    }

    @Test
    void W32_mediaCaptionsLocationsAndReactionsNeverOptOut() {
        Business business = business();
        List<InboundMessage> never = List.of(
                new InboundMessage("image", "{\"id\":\"m1\",\"mime_type\":\"image/jpeg\",\"caption\":\"STOP\"}"),
                new InboundMessage("document", "{\"id\":\"m2\",\"filename\":\"a.pdf\",\"caption\":\"stop\"}"),
                new InboundMessage("video", "{\"id\":\"m3\",\"caption\":\"stop\"}"),
                new InboundMessage("audio", "{\"id\":\"m4\"}"),
                new InboundMessage("location", "{\"latitude\":12.97,\"longitude\":77.59,\"name\":\"STOP\"}"),
                new InboundMessage("reaction", "{\"message_id\":\"wamid.REACTED\",\"emoji\":\"\\ud83d\\uded1\"}"),
                new InboundMessage("sticker", "{\"id\":\"m5\"}"));
        for (InboundMessage message : never) {
            assertThat(optsOut(business, message)).as(message.type()).isFalse();
        }
        // The check itself: only text and interactive replies are read.
        UUID tenant = business.tenant();
        for (InboundMessageType type : InboundMessageType.values()) {
            boolean optOut = inTransaction(tenant, () -> consent.isOptOutMessage(type, "STOP"));
            assertThat(optOut)
                    .as(type.code())
                    .isEqualTo(type == InboundMessageType.TEXT || type == InboundMessageType.INTERACTIVE);
        }
        assertThat(inTransaction(tenant, () -> consent.isOptOutMessage(InboundMessageType.TEXT, null)))
                .isFalse();
    }

    @Test
    void W32_tenantKeywordsOptOutOnlyInTheirOwnTenant() {
        Business mine = business();
        Business theirs = business();
        setKeywords(mine.tenant(), "बंद करो", "Cancel  Messages!", "!!!", "");

        assertThat(optsOut(mine, InboundMessage.text("बंद करो।"))).isTrue();
        assertThat(optsOut(mine, InboundMessage.text("cancel messages"))).isTrue();
        assertThat(optsOut(mine, InboundMessage.text("STOP")))
                .as("the platform's keywords still apply")
                .isTrue();
        assertThat(optsOut(mine, InboundMessage.text("?")))
                .as("a keyword that normalises to nothing never matches")
                .isFalse();
        assertThat(optsOut(mine, InboundMessage.text("cancel messages please"))).isFalse();

        assertThat(optsOut(theirs, InboundMessage.text("cancel messages")))
                .as("another business's keywords never apply")
                .isFalse();
        assertThat(optsOut(theirs, InboundMessage.text("Stop"))).isTrue();
    }

    // What is recorded

    @Test
    void W32_theOptOutEventHoldsIdsOnly() {
        Business business = business();
        String waId = randomWaId();
        String profile = "Profile " + UUID.randomUUID();
        String keyword = "halt " + UUID.randomUUID().toString().substring(0, 8);
        setKeywords(business.tenant(), keyword);
        Customer customer = customer(business, waId);
        Inbound message = inbound(business, waId)
                .profileName(profile)
                .sentAt(Instant.parse("2026-11-21T09:45:30Z"))
                .text(keyword.toUpperCase() + "!");
        UUID row = store(message);
        run(business.tenant(), row);

        UUID contact = customer.contact();
        EventRow event = event(business.tenant(), "optout.received:" + message.wamid());
        for (String personal : List.of(
                keyword, keyword.toUpperCase(), profile, waId, "+" + waId, message.wamid(), business.number())) {
            assertThat(event.payload()).as("the payload holds no message data").doesNotContain(personal);
        }
        JsonNode payload = JSON.readTree(event.payload());
        assertThat(payload.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "webhook_event_id", "contact_id", "conversation_id", "message_id", "lead", "sent_at");
        assertThat(payload.path("webhook_event_id").stringValue()).isEqualTo(row.toString());
        assertThat(payload.path("contact_id").stringValue()).isEqualTo(contact.toString());
        assertThat(payload.path("conversation_id").stringValue())
                .isEqualTo(conversationId(business.tenant(), contact).toString());
        for (String id : List.of("webhook_event_id", "contact_id", "conversation_id", "message_id")) {
            assertThat(payload.path(id).stringValue()).matches(UUID_TEXT);
        }
        assertThat(payload.path("lead").stringValue()).isEqualTo("joined");
        assertThat(event.leadId()).isEqualTo(customer.lead());
        assertThat(payload.path("sent_at").stringValue()).isEqualTo("2026-11-21T09:45:30Z");
    }

    @Test
    void W32_logsNeverCarryTheTextOrTheKeyword(CapturedOutput output) {
        Business business = business();
        String waId = randomWaId();
        String profile = "Profile " + UUID.randomUUID();
        String keyword = "halt " + UUID.randomUUID().toString().substring(0, 8);
        setKeywords(business.tenant(), keyword);
        Customer customer = customer(business, waId);
        contactAction(business, customer, false);
        Inbound tenantKeyword = inbound(business, waId).profileName(profile).text(keyword.toUpperCase() + "!");
        Inbound platformKeyword = inbound(business, waId).profileName(profile).text("Stop promotions!");
        run(business.tenant(), store(tenantKeyword));
        run(business.tenant(), store(platformKeyword));
        // The action service and the worker log their own cancellation.
        UUID refused = contactAction(business, customer, false);

        String logs = output.getAll();
        assertThat(logs).contains("webhook.opt_out").contains("action.cancelled action_id=" + refused);
        for (String secret : List.of(
                keyword,
                keyword.toUpperCase(),
                profile,
                waId,
                "+" + waId,
                business.number(),
                tenantKeyword.wamid(),
                platformKeyword.wamid(),
                "Stop promotions")) {
            assertThat(logs).as("logs must not contain it").doesNotContain(secret);
        }
    }

    // P1: running it again

    @Test
    void P1_rerunningAnOptOutJobChangesNothing(CapturedOutput output) {
        Business business = business();
        Customer customer = customer(business, CUSTOMER_WA_ID);
        UUID action = contactAction(business, customer, false);
        UUID row = store(inbound(business, CUSTOMER_WA_ID).text("STOP"));
        run(business.tenant(), row);
        String before = snapshot(business.tenant(), customer, action);

        run(business.tenant(), row);
        assertThat(snapshot(business.tenant(), customer, action)).isEqualTo(before);
        assertThat(output.getAll()).contains("webhook.already_processed provider=whatsapp webhook_event_id=" + row);

        // A replay with processed_at cleared finds the message already stored and writes nothing else.
        admin.sql("update northstar.webhook_events set processed_at = null where id = :id")
                .param("id", row)
                .update();
        run(business.tenant(), row);
        assertThat(snapshot(business.tenant(), customer, action)).isEqualTo(before);
        assertThat(output.getAll()).contains("webhook.duplicate_message provider=whatsapp webhook_event_id=" + row);
        assertThat(processed(row)).isTrue();

        // Cancelling again for the same contact finds nothing left to cancel.
        assertThat(inTransaction(business.tenant(), () -> cancellation.cancelForOptedOutContact(customer.contact())))
                .isZero();
    }

    @Test
    void P1_concurrentRunsOfAnOptOutJobProcessItOnce(CapturedOutput output) throws Exception {
        Business business = business();
        Customer customer = customer(business, CUSTOMER_WA_ID);
        UUID first = contactAction(business, customer, false);
        UUID second = contactAction(business, customer, true);
        UUID row = store(inbound(business, CUSTOMER_WA_ID).text("STOP"));

        runTogether(business.tenant(), List.of(row, row, row, row));

        assertThat(actionState(first)).startsWith("cancelled|contact_opted_out");
        assertThat(actionState(second)).startsWith("cancelled|contact_opted_out");
        assertThat(eventCountOfType(business.tenant(), "optout.received")).isOne();
        assertThat(output.getAll().split("webhook.opt_out provider=whatsapp webhook_event_id=" + row, -1))
                .as("processed by exactly one run")
                .hasSize(2);
        assertThat(logLine(output, row)).contains("first_opt_out=true cancelled_actions=2");
    }

    // P3: one transaction

    @Test
    void P3_aFailureMidwayRollsBackTheOptOutAndItsCancellations(CapturedOutput output) {
        Business business = business();
        Customer customer = customer(business, CUSTOMER_WA_ID);
        UUID action = contactAction(business, customer, false);
        Inbound stop = inbound(business, CUSTOMER_WA_ID).text("STOP");
        UUID row = store(stop);

        // The event insert comes after the message, the opt-out and the cancellation are written.
        failEventInsertsFor(business.tenant());
        try {
            run(business.tenant(), row);
        } finally {
            stopFailingEventInserts();
        }

        assertThat(processed(row)).as("retried by the sweep").isFalse();
        assertThat(error(row)).isEqualTo("database_conflict");
        assertThat(consentState(customer.contact())).as("no partial opt-out").isEqualTo("unknown");
        assertThat(optOutAt(customer.contact())).isNull();
        assertThat(actionState(action)).as("no partial cancellation").isEqualTo("pending||next attempt|lead");
        assertThat(admin.sql("select count(*) from northstar.messages where provider_message_id = :w")
                        .param("w", stop.wamid())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(output.getAll()).doesNotContain(FAILURE_MARKER);

        run(business.tenant(), row);

        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isNull();
        assertThat(consentState(customer.contact())).isEqualTo("opted_out");
        assertThat(actionState(action)).startsWith("cancelled|contact_opted_out");
    }

    // P9: two tenants

    @Test
    void P9_twoTenantsWithTheSamePhoneOnlyTheSendersTenantIsOptedOut() {
        Business mine = business();
        Business theirs = business();
        Customer myCustomer = customer(mine, CUSTOMER_WA_ID);
        Customer theirCustomer = customer(theirs, CUSTOMER_WA_ID);
        UUID myAction = contactAction(mine, myCustomer, false);
        UUID theirAction = contactAction(theirs, theirCustomer, false);
        setKeywords(theirs.tenant(), "pause please");

        run(mine.tenant(), store(inbound(mine, CUSTOMER_WA_ID).text("pause please")));
        assertThat(consentState(myCustomer.contact()))
                .as("another tenant's keyword never applies")
                .isEqualTo("unknown");

        run(mine.tenant(), store(inbound(mine, CUSTOMER_WA_ID).text("STOP")));

        assertThat(consentState(myCustomer.contact())).isEqualTo("opted_out");
        assertThat(actionState(myAction)).startsWith("cancelled|contact_opted_out");
        assertThat(consentState(theirCustomer.contact())).isEqualTo("unknown");
        assertThat(optOutAt(theirCustomer.contact())).isNull();
        assertThat(actionState(theirAction)).isEqualTo("pending||next attempt|lead");
        assertThat(eventCountOfType(theirs.tenant(), "optout.received")).isZero();
        // Their customer can still opt out of their own business.
        run(theirs.tenant(), store(inbound(theirs, CUSTOMER_WA_ID).text("pause please")));
        assertThat(consentState(theirCustomer.contact())).isEqualTo("opted_out");
    }

    // P10: the write services

    @Test
    void P10_theOptOutWritersJoinTheJobsTransactionAndActOnlyAsTheSystem() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        assertThatThrownBy(() -> SystemScope.call(tenant, () -> consent.recordOptOut(contact, Instant.now())))
                .as("no transaction of their own")
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> SystemScope.call(tenant, () -> cancellation.cancelForOptedOutContact(contact)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> SystemScope.call(tenant, () -> consent.isOptOutMessage(InboundMessageType.TEXT, "x")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> transactions.execute(status -> consent.recordOptOut(contact, Instant.now())))
                .as("no tenant context")
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> transactions.execute(status -> cancellation.cancelForOptedOutContact(contact)))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> transactions.execute(status -> consent.isOptedOut(contact)))
                .isInstanceOf(MissingTenantContextException.class);

        TestData.Member member = data.member("admin");
        TenantContext adminContext = new TenantContext(member.tenantId(), member.userId(), Role.ADMIN);
        UUID adminsContact = data.contact(member.tenantId());
        for (Supplier<Object> write : List.<Supplier<Object>>of(
                () -> consent.recordOptOut(adminsContact, Instant.now()),
                () -> cancellation.cancelForOptedOutContact(adminsContact),
                () -> engineEvents.recordOptOutReceived(new EngineEvents.OptOutReceived(
                        "wamid.TEST",
                        null,
                        EngineEvents.LeadOutcome.NONE,
                        UUID.randomUUID(),
                        adminsContact,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.now())))) {
            assertThatThrownBy(() -> TenantScope.call(adminContext, () -> transactions.execute(status -> write.get())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("system actor");
        }
        assertThat(consentState(adminsContact)).isEqualTo("unknown");
    }

    @Test
    void anOptOutEventNeverOpensALeadAndHasALeadExactlyWhenItJoinedOne() {
        assertThatThrownBy(() -> new EngineEvents.OptOutReceived(
                        "wamid.TEST",
                        UUID.randomUUID(),
                        EngineEvents.LeadOutcome.OPENED,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.now()))
                .hasMessageContaining("never opens a lead");
        assertThatThrownBy(() -> new EngineEvents.OptOutReceived(
                        "wamid.TEST",
                        null,
                        EngineEvents.LeadOutcome.JOINED,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new EngineEvents.OptOutReceived(
                                "wamid.SECRET",
                                null,
                                EngineEvents.LeadOutcome.NONE,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                Instant.now())
                        .toString())
                .doesNotContain("wamid.SECRET");
    }

    // Helpers

    /** A business with a WhatsApp number and one published workflow (version null: none published). */
    private record Business(UUID tenant, String number, UUID version) {}

    private Business business() {
        UUID tenant = data.tenant();
        return new Business(tenant, data.whatsAppNumber(tenant), data.workflowVersion(tenant));
    }

    /** A customer who has written once: their contact, conversation and open lead in New. */
    private record Customer(String waId, UUID contact, UUID conversation, UUID lead) {}

    private Customer customer(Business business, String waId) {
        run(business.tenant(), store(inbound(business, waId).text("Hi, we need a shoot")));
        UUID contact = contactId(business.tenant(), waId);
        UUID lead = admin.sql("select id from northstar.leads where contact_id = :c and closed_at is null")
                .param("c", contact)
                .query(UUID.class)
                .single();
        return new Customer(waId, contact, conversationId(business.tenant(), contact), lead);
    }

    /** One inbound message of a type, with the JSON of its object, from a fresh contact. */
    private record InboundMessage(String type, String contentJson) {
        static InboundMessage text(String body) {
            return new InboundMessage(
                    "text", JSON.createObjectNode().put("body", body).toString());
        }
    }

    /** Sends the message from a new contact and reports whether it opted them out. */
    private boolean optsOut(Business business, InboundMessage message) {
        String waId = randomWaId();
        UUID row = store(inbound(business, waId).typed(message.type(), message.contentJson()));
        run(business.tenant(), row);
        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isNull();
        UUID contact = contactId(business.tenant(), waId);
        String state = consentState(contact);
        assertThat(state).isIn("unknown", "opted_out");
        return state.equals("opted_out");
    }

    private Inbound inbound(Business business, String waId) {
        return WhatsAppPayloads.inbound(business.number()).from(waId);
    }

    private UUID contactAction(Business business, Customer customer, boolean needsReview) {
        return SystemScope.call(
                        business.tenant(),
                        () -> actions.create(new NewAction(
                                CONTACT_TYPE,
                                "test:" + UUID.randomUUID(),
                                needsReview ? ActionMode.APPROVAL : ActionMode.AUTOMATIC,
                                needsReview,
                                null,
                                customer.lead(),
                                null,
                                customer.contact())))
                .action()
                .id();
    }

    private UUID noRecipientAction(Business business, UUID lead) {
        return SystemScope.call(
                        business.tenant(),
                        () -> actions.create(new NewAction(
                                NO_RECIPIENT_TYPE,
                                "test:" + UUID.randomUUID(),
                                ActionMode.AUTOMATIC,
                                false,
                                null,
                                lead,
                                null,
                                null)))
                .action()
                .id();
    }

    private UUID later(UUID action) {
        return update(action, "next_attempt_at = now() + interval '1 day'");
    }

    private UUID waitingForRetry(UUID action) {
        return update(
                action,
                "attempts = 1, last_attempt_at = now(), last_error_class = 'retryable', last_error_code = 'http_503',"
                        + " next_attempt_at = now() + interval '5 minutes'");
    }

    private UUID paused(UUID action) {
        return update(
                action,
                "status = 'paused', next_attempt_at = null, last_error_class = 'auth', last_error_code = 'token_expired'");
    }

    private UUID processing(UUID action) {
        return update(
                action,
                "status = 'processing', attempts = 1, last_attempt_at = now(), next_attempt_at = null,"
                        + " lease_expires_at = now() + interval '5 minutes'");
    }

    private UUID succeeded(UUID action) {
        return update(action, "status = 'succeeded', attempts = 1, next_attempt_at = null, result = '{}'");
    }

    private UUID failed(UUID action) {
        return update(
                action,
                "status = 'failed', attempts = 1, next_attempt_at = null, last_error_class = 'permanent',"
                        + " last_error_code = 'rejected'");
    }

    private UUID update(UUID action, String assignments) {
        admin.sql("update northstar.actions set " + assignments + " where id = :id")
                .param("id", action)
                .update();
        return action;
    }

    /** status|cancel_reason|next attempt or not|lease or not (or lead or not, for a pending action). */
    private String actionState(UUID action) {
        return admin.sql("""
                        select status || '|' || coalesce(cancel_reason, '') || '|'
                               || case when next_attempt_at is null then 'no next attempt' else 'next attempt' end
                               || '|' || case when status = 'pending'
                                              then case when lead_id is null then 'no lead' else 'lead' end
                                              when lease_expires_at is null then 'no lease' else 'lease' end
                               || case when status in ('succeeded', 'failed', 'processing')
                                       then '|' || attempts || '|' || coalesce(last_error_code, '') else '' end
                        from northstar.actions where id = :id""").param("id", action).query(String.class).single();
    }

    private String snapshot(UUID tenant, Customer customer, UUID action) {
        return admin.sql("""
                        select (select consent_state || '|' || opt_out_at || '|' || coalesce(updated_at::text, '')
                                from northstar.contacts where id = :c)
                               || '|' || (select count(*) from northstar.events where tenant_id = :t)
                               || '|' || (select count(*) from northstar.messages where tenant_id = :t)
                               || '|' || (select count(*) from northstar.leads where tenant_id = :t)
                               || '|' || (select status || coalesce(updated_at::text, '')
                                          from northstar.actions where id = :a)""")
                .param("c", customer.contact())
                .param("t", tenant)
                .param("a", action)
                .query(String.class)
                .single();
    }

    /** Sets the tenant's own keywords as an operator would (no settings path writes them yet), one bind each. */
    private void setKeywords(UUID tenant, String... keywords) {
        StringBuilder array = new StringBuilder("array[");
        for (int i = 0; i < keywords.length; i++)
            array.append(i == 0 ? "" : ", ").append(":k").append(i);
        JdbcClient.StatementSpec update = admin.sql(
                        "update northstar.tenants set opt_out_keywords = " + array + "]::text[] where id = :t")
                .param("t", tenant);
        for (int i = 0; i < keywords.length; i++) update = update.param("k" + i, keywords[i]);
        assertThat(update.update()).isOne();
    }

    private <T> T inTransaction(UUID tenant, Supplier<T> work) {
        return SystemScope.call(tenant, () -> transactions.execute(status -> work.get()));
    }

    /** Stores the message through the real intake (step 2.2), as a verified delivery would be, and returns its row. */
    private UUID store(Inbound message) {
        intake.receive(message.json().getBytes(StandardCharsets.UTF_8));
        return admin.sql(
                        "select id from northstar.webhook_events where provider = 'whatsapp' and provider_event_id = :e")
                .param("e", "message:" + message.wamid())
                .query(UUID.class)
                .single();
    }

    /**
     * Runs the job as the worker does: Spring's test support binds a mock request to the test thread, and the system
     * actor is refused on a thread serving a request, so the request is lifted while the job runs.
     */
    private void run(UUID tenant, UUID row) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            handler.run(new ProcessWhatsAppWebhook(tenant, row));
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    /** Runs the jobs on their own threads, all released at once. */
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
                    run(tenant, row);
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

    /**
     * Makes every engine event insert in this tenant fail with a serialization failure (SQLSTATE 40001), through a
     * test-only trigger created as migrator, the tables' owner. Always paired with {@link #stopFailingEventInserts()}.
     */
    private void failEventInsertsFor(UUID tenant) {
        asMigrator(
                """
                create function northstar.test_fail_event_insert() returns trigger language plpgsql as $$
                begin
                  raise exception '%s' using errcode = '40001';
                end
                $$""".formatted(FAILURE_MARKER),
                "create trigger test_fail_event_insert before insert on northstar.events for each row"
                        + " when (new.tenant_id = '" + tenant
                        + "') execute function northstar.test_fail_event_insert()");
    }

    private void stopFailingEventInserts() {
        asMigrator(
                "drop trigger if exists test_fail_event_insert on northstar.events",
                "drop function if exists northstar.test_fail_event_insert()");
    }

    private void asMigrator(String... statements) {
        JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
        new TransactionTemplate(new DataSourceTransactionManager(postgres.dataSource()))
                .executeWithoutResult(status -> {
                    jdbc.sql("set local role migrator").update();
                    for (String statement : statements) jdbc.sql(statement).update();
                });
    }

    private static String randomWaId() {
        return "91" + (9_000_000_000L + (long) (Math.random() * 999_999_999L));
    }

    /** The job's webhook.opt_out line for this row. */
    private static String logLine(CapturedOutput output, UUID row) {
        Matcher line = Pattern.compile("webhook\\.opt_out provider=whatsapp webhook_event_id=" + row + "[^\\n]*")
                .matcher(output.getAll());
        assertThat(line.find()).as("logged").isTrue();
        return line.group();
    }

    private boolean processed(UUID row) {
        return Boolean.TRUE.equals(
                admin.sql("select processed_at is not null from northstar.webhook_events where id = :id")
                        .param("id", row)
                        .query(Boolean.class)
                        .single());
    }

    private String error(UUID row) {
        List<String> errors = admin.sql("select error from northstar.webhook_events where id = :id")
                .param("id", row)
                .query((rs, i) -> rs.getString("error"))
                .list();
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    private UUID contactId(UUID tenant, String waId) {
        return admin.sql("select id from northstar.contacts where tenant_id = :t and normalized_phone = :p")
                .param("t", tenant)
                .param("p", "+" + waId)
                .query(UUID.class)
                .single();
    }

    private String consentState(UUID contact) {
        return admin.sql("select consent_state from northstar.contacts where id = :id")
                .param("id", contact)
                .query(String.class)
                .single();
    }

    private OffsetDateTime optOutAt(UUID contact) {
        List<OffsetDateTime> times = admin.sql("select opt_out_at from northstar.contacts where id = :id")
                .param("id", contact)
                .query((rs, i) -> rs.getObject("opt_out_at", OffsetDateTime.class))
                .list();
        assertThat(times).hasSize(1);
        return times.getFirst();
    }

    private UUID conversationId(UUID tenant, UUID contact) {
        return admin.sql("select id from northstar.conversations where tenant_id = :t and contact_id = :c")
                .param("t", tenant)
                .param("c", contact)
                .query(UUID.class)
                .single();
    }

    private OffsetDateTime lastInboundAt(UUID conversation) {
        return admin.sql("select last_inbound_at from northstar.conversations where id = :id")
                .param("id", conversation)
                .query((rs, i) -> rs.getObject("last_inbound_at", OffsetDateTime.class))
                .single();
    }

    /** stage|previous_stage|stage_entered_at|version|open */
    private String leadState(UUID lead) {
        return admin.sql("""
                        select stage || '|' || coalesce(previous_stage, '') || '|' || coalesce(stage_entered_at::text, '')
                               || '|' || version || '|' || (closed_at is null)::text
                        from northstar.leads where id = :id""").param("id", lead).query(String.class).single();
    }

    private long leadCount(UUID tenant, UUID contact) {
        return admin.sql("select count(*) from northstar.leads where tenant_id = :t and contact_id = :c")
                .param("t", tenant)
                .param("c", contact)
                .query(Long.class)
                .single();
    }

    private long count(String table, UUID tenant) {
        return admin.sql("select count(*) from northstar." + table + " where tenant_id = :t")
                .param("t", tenant)
                .query(Long.class)
                .single();
    }

    private record EventRow(UUID leadId, String type, String payload, boolean processed) {}

    private EventRow event(UUID tenant, String dedupeKey) {
        return admin.sql("""
                        select lead_id, type, payload::text as payload, processed_at is not null as done
                        from northstar.events where tenant_id = :t and dedupe_key = :k""")
                .param("t", tenant)
                .param("k", dedupeKey)
                .query((rs, i) -> new EventRow(
                        rs.getObject("lead_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("payload"),
                        rs.getBoolean("done")))
                .single();
    }

    private long eventCount(UUID tenant, String dedupeKey) {
        return admin.sql("select count(*) from northstar.events where tenant_id = :t and dedupe_key = :k")
                .param("t", tenant)
                .param("k", dedupeKey)
                .query(Long.class)
                .single();
    }

    private long eventCountOfType(UUID tenant, String type) {
        return admin.sql("select count(*) from northstar.events where tenant_id = :t and type = :type")
                .param("t", tenant)
                .param("type", type)
                .query(Long.class)
                .single();
    }
}
