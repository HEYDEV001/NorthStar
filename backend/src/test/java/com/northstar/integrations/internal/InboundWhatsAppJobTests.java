package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.contacts.InboundContacts;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.fakes.WhatsAppPayloads.Inbound;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineEvents;
import com.northstar.workflow.LeadIntake;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * The inbound WhatsApp job (step 2.4b, integration-specs.md section 1 step 6) against real Postgres with RLS: each
 * stored message becomes a contact, its source, its conversation, the message, the lead it opens or joins, and one
 * engine event, all in one transaction as the system actor. Rows are stored through the real intake (step 2.2), so the
 * job reads exactly the slice that is stored, then run as the worker runs them. The worker is off in this context.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InboundWhatsAppJobTests {

    /** The fake customer of {@link WhatsAppPayloads}, as WhatsApp sends it and as contacts store it. */
    private static final String CUSTOMER_WA_ID = "910000000001";

    private static final String CUSTOMER = "+" + CUSTOMER_WA_ID;

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The message of the injected lead-insert failure, which must never reach a log. */
    private static final String FAILURE_MARKER = "injected-failure-" + UUID.randomUUID();

    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler handler;
    private final InboundContacts contacts;
    private final LeadIntake leadIntake;
    private final EngineEvents engineEvents;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;

    InboundWhatsAppJobTests(
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler handler,
            @Autowired InboundContacts contacts,
            @Autowired LeadIntake leadIntake,
            @Autowired EngineEvents engineEvents,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.intake = intake;
        this.handler = handler;
        this.contacts = contacts;
        this.leadIntake = leadIntake;
        this.engineEvents = engineEvents;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    // A first message

    @Test
    void aFirstMessageCreatesTheContactItsConversationAndALeadInNew(CapturedOutput output) {
        Business business = business();
        Instant sentAt = Instant.parse("2026-11-20T09:30:00Z");
        Inbound message =
                WhatsAppPayloads.inbound(business.number()).sentAt(sentAt).text("Need a shoot on Saturday");
        UUID row = store(message);

        run(business.tenant(), row);

        UUID contact = contactId(business.tenant(), CUSTOMER);
        assertThat(contactName(contact)).isEqualTo("Test Customer");
        assertThat(admin.sql("""
                        select source || '|' || (first_seen_at = :sentAt)::text || '|'
                               || (chat_link_id is null and ad_referral_id is null)::text
                        from northstar.contact_sources where contact_id = :contact""")
                        .param("sentAt", OffsetDateTime.parse("2026-11-20T09:30:00Z"))
                        .param("contact", contact)
                        .query(String.class)
                        .list())
                .as("one direct source, first seen at WhatsApp's timestamp")
                .containsExactly("direct|true|true");

        ConversationRow conversation = conversation(business.tenant(), contact);
        assertThat(conversation.number()).isEqualTo(business.number());
        assertThat(conversation.assignee()).isNull();
        assertThat(conversation.lastInboundAt().toInstant()).isEqualTo(sentAt);
        assertThat(conversation.windowExpiresAt().toInstant()).isEqualTo(sentAt.plus(Duration.ofHours(24)));

        List<MessageRow> messages = messages(conversation.id());
        assertThat(messages).singleElement().satisfies(stored -> {
            assertThat(stored.direction()).isEqualTo("in");
            assertThat(stored.senderType()).isEqualTo("contact");
            assertThat(stored.wamid()).isEqualTo(message.wamid());
            assertThat(stored.type()).isEqualTo("text");
            assertThat(stored.body()).isEqualTo("Need a shoot on Saturday");
            assertThat(stored.sentAt().toInstant()).isEqualTo(sentAt);
            assertThat(stored.status()).isNull();
        });

        LeadRow lead = onlyLead(business.tenant(), contact);
        assertThat(lead.stage()).isEqualTo("new");
        assertThat(lead.origin()).isEqualTo("direct");
        assertThat(lead.workflowVersionId()).isEqualTo(business.version());
        assertThat(lead.conversationId()).isEqualTo(conversation.id());
        assertThat(lead.assignee()).isNull();
        assertThat(lead.version()).isZero();
        assertThat(lead.closed()).isFalse();

        EventRow event = engineEvent(business.tenant(), message.wamid());
        assertThat(event.type()).isEqualTo("message.received");
        assertThat(event.dedupeKey()).isEqualTo("message.received:" + message.wamid());
        assertThat(event.leadId()).isEqualTo(lead.id());
        assertThat(event.processed()).as("the engine (Phase 3) processes it").isFalse();
        JsonNode payload = JSON.readTree(event.payload());
        assertThat(payload.path("webhook_event_id").stringValue()).isEqualTo(row.toString());
        assertThat(payload.path("contact_id").stringValue()).isEqualTo(contact.toString());
        assertThat(payload.path("conversation_id").stringValue())
                .isEqualTo(conversation.id().toString());
        assertThat(payload.path("message_id").stringValue())
                .isEqualTo(messages.getFirst().id().toString());
        assertThat(payload.path("message_type").stringValue()).isEqualTo("text");
        assertThat(payload.path("lead").stringValue()).isEqualTo("opened");
        assertThat(payload.path("sent_at").stringValue()).isEqualTo("2026-11-20T09:30:00Z");

        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isNull();
        assertThat(output.getAll())
                .contains("webhook.processed provider=whatsapp webhook_event_id=" + row + " message_id="
                        + messages.getFirst().id() + " lead_id=" + lead.id() + " lead=opened");
    }

    // W1: the open lead

    @Test
    void W1_aMessageFromAContactWithAnOpenLeadJoinsItsConversationAndOpensNoLead(CapturedOutput output) {
        Business business = business();
        UUID staff = data.membership(business.tenant(), data.user(), "staff", "active");
        UUID contact = data.contact(business.tenant(), CUSTOMER, "Asha");
        UUID conversation = data.conversation(business.tenant(), contact, business.number(), staff);
        UUID lead = data.lead(business.tenant(), business.version(), contact, conversation, staff);
        Inbound message = WhatsAppPayloads.inbound(business.number()).text("Is Saturday still free?");
        UUID row = store(message);

        run(business.tenant(), row);

        LeadRow only = onlyLead(business.tenant(), contact);
        assertThat(only.id()).isEqualTo(lead);
        assertThat(only.stage()).as("the engine moves leads, not this job").isEqualTo("qualifying");
        assertThat(only.version()).as("nothing on the lead changed").isZero();
        assertThat(messages(conversation))
                .singleElement()
                .satisfies(stored -> assertThat(stored.body()).isEqualTo("Is Saturday still free?"));
        assertThat(count("conversations", business.tenant())).isOne();
        assertThat(engineEvent(business.tenant(), message.wamid()).leadId()).isEqualTo(lead);
        assertThat(JSON.readTree(engineEvent(business.tenant(), message.wamid()).payload())
                        .path("lead")
                        .stringValue())
                .isEqualTo("joined");
        assertThat(output.getAll()).contains("lead_id=" + lead + " lead=joined");
    }

    @Test
    void W1_anOpenLeadWithoutAConversationIsLinkedToTheNewOneWhichTakesItsAssignee() {
        Business business = business();
        UUID staff = data.membership(business.tenant(), data.user(), "staff", "active");
        UUID contact = data.contact(business.tenant(), CUSTOMER, null);
        UUID lead = data.lead(business.tenant(), business.version(), contact, null, staff); // entered by hand
        UUID row = store(WhatsAppPayloads.inbound(business.number()));

        run(business.tenant(), row);

        ConversationRow conversation = conversation(business.tenant(), contact);
        assertThat(conversation.assignee())
                .as("a new conversation takes the open lead's assignee")
                .isEqualTo(staff);
        LeadRow linked = onlyLead(business.tenant(), contact);
        assertThat(linked.id()).isEqualTo(lead);
        assertThat(linked.conversationId()).isEqualTo(conversation.id());
        assertThat(linked.version())
                .as("linking is a change, so the optimistic lock moves on")
                .isOne();
        assertThat(linked.assignee()).isEqualTo(staff);
        assertThat(messages(conversation.id())).hasSize(1);
    }

    // W2: a closed lead

    @Test
    void W2_aContactWhoseLeadIsLostOrJunkGetsANewLeadInTheSameConversation() {
        for (String closing : List.of("lost", "junk", "completed")) {
            Business business = business();
            UUID contact = data.contact(business.tenant(), CUSTOMER, "Asha");
            UUID conversation = data.conversation(business.tenant(), contact, business.number(), null);
            UUID old = data.lead(business.tenant(), business.version(), contact, conversation, null);
            data.closeLead(old, closing);
            Inbound message = WhatsAppPayloads.inbound(business.number()).text("Back again");
            UUID row = store(message);

            run(business.tenant(), row);

            List<LeadRow> leads = leads(business.tenant(), contact);
            assertThat(leads).as(closing).hasSize(2);
            LeadRow previous =
                    leads.stream().filter(l -> l.id().equals(old)).findFirst().orElseThrow();
            LeadRow fresh =
                    leads.stream().filter(l -> !l.id().equals(old)).findFirst().orElseThrow();
            assertThat(previous.stage()).as("the old lead and its history stay").isEqualTo(closing);
            assertThat(previous.closed()).isTrue();
            assertThat(fresh.stage())
                    .as("in New; 3.10 moves returning contacts on")
                    .isEqualTo("new");
            assertThat(fresh.closed()).isFalse();
            assertThat(fresh.conversationId())
                    .as("same contact, same conversation")
                    .isEqualTo(conversation);
            assertThat(count("conversations", business.tenant())).isOne();
            assertThat(engineEvent(business.tenant(), message.wamid()).leadId()).isEqualTo(fresh.id());
        }
    }

    // P3: one lead, one transaction

    @Test
    void P3_twoMessagesFromANewContactArrivingTogetherOpenOneLead() throws Exception {
        for (int together : List.of(2, 8)) {
            Business business = business();
            List<UUID> rows = new ArrayList<>();
            List<String> wamids = new ArrayList<>();
            for (int i = 0; i < together; i++) {
                Inbound message = WhatsAppPayloads.inbound(business.number()).text("Message " + i);
                rows.add(store(message));
                wamids.add(message.wamid());
            }

            runTogether(business.tenant(), rows);

            UUID contact = contactId(business.tenant(), CUSTOMER);
            LeadRow lead = onlyLead(business.tenant(), contact);
            ConversationRow conversation = conversation(business.tenant(), contact);
            assertThat(count("contacts", business.tenant()))
                    .as(together + " together")
                    .isOne();
            assertThat(count("conversations", business.tenant())).isOne();
            assertThat(count("contact_sources", business.tenant())).isOne();
            assertThat(messages(conversation.id())).hasSize(together);
            List<EventRow> events = wamids.stream()
                    .map(wamid -> engineEvent(business.tenant(), wamid))
                    .toList();
            assertThat(events).allSatisfy(event -> assertThat(event.leadId()).isEqualTo(lead.id()));
            assertThat(events.stream()
                            .filter(event -> "opened"
                                    .equals(JSON.readTree(event.payload())
                                            .path("lead")
                                            .stringValue()))
                            .count())
                    .as("one message opened the lead and the others joined it")
                    .isOne();
            for (UUID row : rows) {
                assertThat(processed(row)).isTrue();
                assertThat(error(row)).isNull();
            }
        }
    }

    @Test
    void P3_aFailureMidwayLeavesNothingAndTheRetryCompletesIt(CapturedOutput output) {
        Business business = business();
        UUID row = store(WhatsAppPayloads.inbound(business.number()));

        // The lead insert comes after the contact, source, conversation and message are written.
        failLeadInsertsFor(business.tenant());
        try {
            run(business.tenant(), row);
        } finally {
            stopFailingLeadInserts();
        }

        assertThat(processed(row)).as("retried by the sweep").isFalse();
        assertThat(error(row)).isEqualTo("database_conflict");
        assertThat(output.getAll())
                .contains("webhook.failed provider=whatsapp webhook_event_id=" + row
                        + " error=database_conflict final=false");
        for (String table : List.of("contacts", "contact_sources", "conversations", "messages", "leads", "events")) {
            assertThat(count(table, business.tenant()))
                    .as("no partial " + table)
                    .isZero();
        }

        run(business.tenant(), row);

        assertThat(processed(row)).isTrue();
        assertThat(error(row)).as("a successful retry clears the failure").isNull();
        UUID contact = contactId(business.tenant(), CUSTOMER);
        assertThat(onlyLead(business.tenant(), contact).stage()).isEqualTo("new");
        assertThat(messages(conversation(business.tenant(), contact).id())).hasSize(1);
        assertThat(count("events", business.tenant())).isOne();
    }

    // P1: running it again

    @Test
    void P1_rerunningAProcessedJobChangesNothing(CapturedOutput output) {
        Business business = business();
        UUID row = store(WhatsAppPayloads.inbound(business.number()));
        run(business.tenant(), row);
        OffsetDateTime processedAt = processedAt(row);
        Totals before = totals(business.tenant());

        run(business.tenant(), row);

        assertThat(totals(business.tenant())).isEqualTo(before);
        assertThat(processedAt(row)).isEqualTo(processedAt);
        assertThat(output.getAll()).contains("webhook.already_processed provider=whatsapp webhook_event_id=" + row);
    }

    @Test
    void P1_concurrentRunsOfOneJobProcessItOnce() throws Exception {
        Business business = business();
        UUID row = store(WhatsAppPayloads.inbound(business.number()));

        runTogether(business.tenant(), List.of(row, row, row, row));

        assertThat(totals(business.tenant())).isEqualTo(new Totals(1, 1, 1, 1, 1, 1));
        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isNull();
    }

    @Test
    void P1_replayingWithProcessedAtClearedStoresNothingTwiceAndOpensNoLead(CapturedOutput output) {
        Business business = business();
        UUID row = store(WhatsAppPayloads.inbound(business.number()));
        run(business.tenant(), row);
        UUID contact = contactId(business.tenant(), CUSTOMER);
        // Even with the lead closed meanwhile, an old message never opens a new one.
        data.closeLead(onlyLead(business.tenant(), contact).id(), "lost");
        Totals before = totals(business.tenant());
        admin.sql("update northstar.webhook_events set processed_at = null where id = :id")
                .param("id", row)
                .update();

        run(business.tenant(), row);

        assertThat(totals(business.tenant())).isEqualTo(before);
        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isNull();
        assertThat(output.getAll()).contains("webhook.duplicate_message provider=whatsapp webhook_event_id=" + row);
    }

    // P4: the sender's phone

    @Test
    void P4_aSenderWithoutAValidPhoneFailsPermanentlyAndStoresNothing(CapturedOutput output) {
        Business business = business();
        List<Inbound> invalid = List.of(
                WhatsAppPayloads.inbound(business.number()).from(null),
                WhatsAppPayloads.inbound(business.number()).from("0123456789"),
                WhatsAppPayloads.inbound(business.number()).from("12345"),
                WhatsAppPayloads.inbound(business.number()).from("+910000000001"),
                WhatsAppPayloads.inbound(business.number()).from("some.username"));
        for (Inbound message : invalid) {
            UUID row = store(message);

            run(business.tenant(), row);

            assertThat(processed(row)).as("never retried").isTrue();
            assertThat(error(row)).isEqualTo("contact_phone_invalid");
            assertThat(output.getAll())
                    .contains("webhook.failed provider=whatsapp webhook_event_id=" + row
                            + " error=contact_phone_invalid final=true");
        }
        assertThat(totals(business.tenant())).isEqualTo(new Totals(0, 0, 0, 0, 0, 0));
    }

    @Test
    void aSliceThatCannotBeUsedIsClosedAsPayloadInvalidAndStoresNothing() {
        Business business = business();
        String wamid = WhatsAppPayloads.wamid();
        // A row whose slice holds a different message than its key says.
        String otherSlice = WhatsAppDelivery.parse(
                        WhatsAppPayloads.inbound(business.number()).json().getBytes(StandardCharsets.UTF_8))
                .items()
                .getFirst()
                .payloadJson();
        UUID row = admin.sql("""
                        insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)
                        values (:tenant, 'whatsapp', :key, cast(:payload as jsonb)) returning id""")
                .param("tenant", business.tenant())
                .param("key", "message:" + wamid)
                .param("payload", otherSlice)
                .query(UUID.class)
                .single();

        run(business.tenant(), row);

        assertThat(processed(row)).isTrue();
        assertThat(error(row)).isEqualTo("payload_invalid");
        assertThat(totals(business.tenant())).isEqualTo(new Totals(0, 0, 0, 0, 0, 0));
    }

    // P9: tenants stay apart

    @Test
    void P9_aBatchForTwoTenantsIsProcessedIntoEachTenantOnly() {
        Business first = business();
        Business second = business();
        String firstWamid = WhatsAppPayloads.wamid();
        String secondWamid = WhatsAppPayloads.wamid();
        // The same customer writes to both businesses, in one delivery.
        intake.receive(WhatsAppPayloads.delivery()
                .message(first.number(), firstWamid, "For the first business")
                .message(second.number(), secondWamid, "For the second business")
                .json()
                .getBytes(StandardCharsets.UTF_8));
        UUID firstRow = webhookRow("message:" + firstWamid);
        UUID secondRow = webhookRow("message:" + secondWamid);

        run(first.tenant(), firstRow);
        run(second.tenant(), secondRow);

        UUID firstContact = contactId(first.tenant(), CUSTOMER);
        UUID secondContact = contactId(second.tenant(), CUSTOMER);
        assertThat(firstContact).as("one contact per tenant").isNotEqualTo(secondContact);
        assertThat(messages(conversation(first.tenant(), firstContact).id()))
                .extracting(MessageRow::body)
                .containsExactly("For the first business");
        assertThat(messages(conversation(second.tenant(), secondContact).id()))
                .extracting(MessageRow::body)
                .containsExactly("For the second business");
        assertThat(onlyLead(first.tenant(), firstContact).workflowVersionId()).isEqualTo(first.version());
        assertThat(onlyLead(second.tenant(), secondContact).workflowVersionId()).isEqualTo(second.version());
        assertThat(eventKeys(first.tenant())).containsExactly("message.received:" + firstWamid);
        assertThat(eventKeys(second.tenant())).containsExactly("message.received:" + secondWamid);
        assertThat(totals(first.tenant())).isEqualTo(new Totals(1, 1, 1, 1, 1, 1));
        assertThat(totals(second.tenant())).isEqualTo(new Totals(1, 1, 1, 1, 1, 1));
    }

    @Test
    void P9_aJobNamingAnotherTenantsWebhookEventFailsLoudlyAndWritesNothing(CapturedOutput output) {
        Business owner = business();
        Business other = business();
        UUID row = store(WhatsAppPayloads.inbound(owner.number()));

        assertThatThrownBy(() -> run(other.tenant(), row)).isInstanceOf(IllegalStateException.class);

        assertThat(output.getAll()).contains("webhook.event_missing webhook_event_id=" + row);
        assertThat(processed(row)).isFalse();
        assertThat(error(row)).as("nothing recorded on the owner's row").isNull();
        assertThat(totals(owner.tenant())).isEqualTo(new Totals(0, 0, 0, 0, 0, 0));
        assertThat(totals(other.tenant())).isEqualTo(new Totals(0, 0, 0, 0, 0, 0));
    }

    // P10: no tenant context

    @Test
    void P10_withoutATenantContextTheJobFailsLoudlyAndRecordsNothing(CapturedOutput output) {
        Business business = business();
        UUID row = store(WhatsAppPayloads.inbound(business.number()));

        // The job's body run outside the tenant scope TenantJobHandler always opens, as a bug would.
        assertThatThrownBy(() -> handler.runForTenant(new ProcessWhatsAppWebhook(business.tenant(), row)))
                .isInstanceOf(MissingTenantContextException.class);

        assertThat(processed(row)).isFalse();
        assertThat(error(row)).as("never recorded as a webhook failure").isNull();
        assertThat(output.getAll()).doesNotContain("webhook_event_id=" + row + " error=");
        assertThat(totals(business.tenant())).isEqualTo(new Totals(0, 0, 0, 0, 0, 0));
    }

    // Reactions and system messages

    @Test
    void reactionAndSystemMessagesAreStoredButNeverOpenALeadOnTheirOwn() {
        Business business = business();
        UUID reaction = store(WhatsAppPayloads.inbound(business.number())
                .typed("reaction", "{\"message_id\":\"wamid.REACTED\",\"emoji\":\"\\ud83d\\udc4d\"}"));
        UUID system = store(WhatsAppPayloads.inbound(business.number())
                .typed("system", "{\"body\":\"Number changed\",\"type\":\"customer_changed_number\"}"));

        run(business.tenant(), reaction);
        run(business.tenant(), system);

        UUID contact = contactId(business.tenant(), CUSTOMER);
        ConversationRow conversation = conversation(business.tenant(), contact);
        assertThat(messages(conversation.id())).hasSize(2).allSatisfy(stored -> {
            assertThat(stored.type()).isEqualTo("unsupported");
            assertThat(stored.body()).isNull();
        });
        assertThat(leads(business.tenant(), contact)).isEmpty();
        for (UUID row : List.of(reaction, system)) {
            assertThat(processed(row)).isTrue();
            assertThat(error(row)).as("processed in full").isNull();
        }
        assertThat(count("events", business.tenant())).isEqualTo(2);
        assertThat(admin.sql("select count(*) from northstar.events where tenant_id = :t and lead_id is null"
                                + " and payload ->> 'lead' = 'none'")
                        .param("t", business.tenant())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        // Another unsupported type follows the normal rule and opens one; a reaction then joins it.
        Inbound sticker = WhatsAppPayloads.inbound(business.number()).typed("sticker", "{\"id\":\"media-1\"}");
        run(business.tenant(), store(sticker));
        UUID lead = onlyLead(business.tenant(), contact).id();
        Inbound laterReaction = WhatsAppPayloads.inbound(business.number())
                .typed("reaction", "{\"message_id\":\"wamid.REACTED\",\"emoji\":\"\\u2764\"}");
        run(business.tenant(), store(laterReaction));
        assertThat(engineEvent(business.tenant(), laterReaction.wamid()).leadId())
                .isEqualTo(lead);
        assertThat(leads(business.tenant(), contact)).hasSize(1);
    }

    // No published workflow

    @Test
    void aTenantWithNoSinglePublishedWorkflowStoresTheMessageWithoutALead(CapturedOutput output) {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        Inbound first = WhatsAppPayloads.inbound(number);
        UUID none = store(first);

        run(tenant, none);

        UUID contact = contactId(tenant, CUSTOMER);
        assertThat(leads(tenant, contact)).isEmpty();
        assertThat(messages(conversation(tenant, contact).id())).hasSize(1);
        assertThat(count("contact_sources", tenant)).isOne();
        assertThat(engineEvent(tenant, first.wamid()).leadId()).isNull();
        assertThat(processed(none)).isTrue();
        assertThat(error(none)).isEqualTo("no_published_workflow");
        assertThat(output.getAll())
                .contains("webhook.no_published_workflow provider=whatsapp webhook_event_id=" + none);

        // More than one published workflow is no single one either.
        UUID version = data.workflowVersion(tenant);
        data.workflowVersion(tenant);
        UUID two = store(WhatsAppPayloads.inbound(number));
        run(tenant, two);
        assertThat(leads(tenant, contact)).isEmpty();
        assertThat(error(two)).isEqualTo("no_published_workflow");

        // An open lead is joined whatever is published: joining needs no workflow.
        UUID lead = data.lead(
                tenant, version, contact, conversation(tenant, contact).id(), null);
        Inbound joining = WhatsAppPayloads.inbound(number);
        UUID joins = store(joining);
        run(tenant, joins);
        assertThat(error(joins)).isNull();
        assertThat(engineEvent(tenant, joining.wamid()).leadId()).isEqualTo(lead);
    }

    // Assignment

    @Test
    void aNewLeadTakesItsConversationsAssigneeAndNoExistingAssigneeIsChanged() {
        Business business = business();
        UUID staff = data.membership(business.tenant(), data.user(), "staff", "active");
        UUID contact = data.contact(business.tenant(), CUSTOMER, "Asha");
        data.conversation(business.tenant(), contact, business.number(), staff); // no lead yet

        run(business.tenant(), store(WhatsAppPayloads.inbound(business.number())));

        assertThat(onlyLead(business.tenant(), contact).assignee()).isEqualTo(staff);
        assertThat(conversation(business.tenant(), contact).assignee()).isEqualTo(staff);

        // A mismatch already there is left for 3.9.
        Business mismatched = business();
        UUID first = data.membership(mismatched.tenant(), data.user(), "staff", "active");
        UUID second = data.membership(mismatched.tenant(), data.user(), "staff", "active");
        UUID otherContact = data.contact(mismatched.tenant(), CUSTOMER, "Ravi");
        UUID otherConversation = data.conversation(mismatched.tenant(), otherContact, mismatched.number(), first);
        UUID lead = data.lead(mismatched.tenant(), mismatched.version(), otherContact, otherConversation, second);

        run(mismatched.tenant(), store(WhatsAppPayloads.inbound(mismatched.number())));

        assertThat(conversation(mismatched.tenant(), otherContact).assignee()).isEqualTo(first);
        LeadRow unchanged = onlyLead(mismatched.tenant(), otherContact);
        assertThat(unchanged.id()).isEqualTo(lead);
        assertThat(unchanged.assignee()).isEqualTo(second);
    }

    // The contact's name

    @Test
    void theProfileNameFillsAnEmptyNameOnlyAndIsCleaned() {
        Business fresh = business();
        run(fresh.tenant(), store(WhatsAppPayloads.inbound(fresh.number()).profileName("  Asha \t Rao\u0007  ")));
        assertThat(contactName(contactId(fresh.tenant(), CUSTOMER))).isEqualTo("Asha Rao");

        Business named = business();
        UUID kept = data.contact(named.tenant(), CUSTOMER, "Asha Rao (bride)");
        run(named.tenant(), store(WhatsAppPayloads.inbound(named.number()).profileName("Someone Else")));
        assertThat(contactName(kept)).as("never overwritten").isEqualTo("Asha Rao (bride)");

        for (String empty : new String[] {null, "   "}) {
            Business unnamed = business();
            UUID filled = data.contact(unnamed.tenant(), CUSTOMER, empty);
            run(
                    unnamed.tenant(),
                    store(WhatsAppPayloads.inbound(unnamed.number()).profileName("Ravi")));
            assertThat(contactName(filled)).isEqualTo("Ravi");
        }

        Business noProfile = business();
        run(
                noProfile.tenant(),
                store(WhatsAppPayloads.inbound(noProfile.number()).withoutContacts()));
        assertThat(contactName(contactId(noProfile.tenant(), CUSTOMER))).isNull();
    }

    // The 24-hour window

    @Test
    void lastInboundAtOnlyMovesForward() {
        Business business = business();
        Instant early = Instant.parse("2026-11-20T10:00:00Z");
        Instant later = Instant.parse("2026-11-20T10:05:00Z");
        Instant latest = Instant.parse("2026-11-20T10:10:00Z");

        run(business.tenant(), store(WhatsAppPayloads.inbound(business.number()).sentAt(later)));
        // A message WhatsApp sent earlier, delivered late.
        run(business.tenant(), store(WhatsAppPayloads.inbound(business.number()).sentAt(early)));
        UUID contact = contactId(business.tenant(), CUSTOMER);
        assertThat(conversation(business.tenant(), contact).lastInboundAt().toInstant())
                .isEqualTo(later);

        run(business.tenant(), store(WhatsAppPayloads.inbound(business.number()).sentAt(latest)));
        assertThat(conversation(business.tenant(), contact).lastInboundAt().toInstant())
                .isEqualTo(latest);
        assertThat(messages(conversation(business.tenant(), contact).id()))
                .extracting(stored -> stored.sentAt().toInstant())
                .containsExactlyInAnyOrder(early, later, latest);
    }

    // Closed conversations (step 2.9a): a new inbound message reopens one

    @Test
    void aNewInboundMessageReopensAClosedConversationAndMovesItsLatestActivity(CapturedOutput output) {
        Business business = business();
        UUID first = store(WhatsAppPayloads.inbound(business.number()).text("First"));
        run(business.tenant(), first);
        assertThat(output.getAll())
                .contains("webhook.processed provider=whatsapp webhook_event_id=" + first)
                .containsPattern("webhook_event_id=" + first + " [^\\n]* reopened=false");
        UUID conversation = conversation(business.tenant(), contactId(business.tenant(), CUSTOMER))
                .id();
        close(conversation);

        UUID back = store(WhatsAppPayloads.inbound(business.number()).text("Back again"));
        run(business.tenant(), back);

        assertThat(conversationStatus(conversation)).isEqualTo("open");
        assertThat(latestActivityIsTheNewestMessage(conversation)).isTrue();
        assertThat(messages(conversation)).hasSize(2);
        assertThat(output.getAll())
                .containsPattern(
                        "webhook.processed provider=whatsapp webhook_event_id=" + back + " [^\\n]* reopened=true");
    }

    @Test
    void everyInboundTypeReopensItAnOptOutAndAReactionIncluded(CapturedOutput output) {
        Business business = business();
        run(business.tenant(), store(WhatsAppPayloads.inbound(business.number()).text("Hi")));
        UUID conversation = conversation(business.tenant(), contactId(business.tenant(), CUSTOMER))
                .id();

        close(conversation);
        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number())
                        .typed("reaction", "{\"message_id\":\"wamid.REACTED\",\"emoji\":\"\\u2764\"}")));
        assertThat(conversationStatus(conversation)).as("a reaction").isEqualTo("open");

        close(conversation);
        UUID stop = store(WhatsAppPayloads.inbound(business.number()).text("STOP"));
        run(business.tenant(), stop);
        assertThat(conversationStatus(conversation)).as("an opt-out").isEqualTo("open");
        assertThat(latestActivityIsTheNewestMessage(conversation)).isTrue();
        assertThat(output.getAll())
                .containsPattern(
                        "webhook.opt_out provider=whatsapp webhook_event_id=" + stop + " [^\\n]* reopened=true");
    }

    @Test
    void W32_whenTheClientLastWroteSkipsReactionsAndSystemMessagesAndOnlyMovesForward() {
        Business business = business();
        Instant first = Instant.parse("2026-11-20T10:00:00Z");
        Instant later = Instant.parse("2026-11-20T10:05:00Z");
        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number()).sentAt(first).text("Hi")));
        UUID conversation = conversation(business.tenant(), contactId(business.tenant(), CUSTOMER))
                .id();
        assertThat(lastContactMessageAt(conversation)).isEqualTo(first);

        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number())
                        .sentAt(later)
                        .typed("reaction", "{\"message_id\":\"wamid.REACTED\",\"emoji\":\"\\u2764\"}")));
        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number())
                        .sentAt(later.plusSeconds(60))
                        .typed("system", "{\"body\":\"Number changed\",\"type\":\"customer_changed_number\"}")));
        assertThat(lastContactMessageAt(conversation))
                .as("step 2.9b: a reaction or a system message is not the client writing")
                .isEqualTo(first);
        assertThat(conversation(business.tenant(), contactId(business.tenant(), CUSTOMER))
                        .lastInboundAt()
                        .toInstant())
                .as("while every inbound message still moves the window")
                .isEqualTo(later.plusSeconds(60));

        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number()).sentAt(later).text("Photo?")));
        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number()).sentAt(first).text("Late copy")));
        assertThat(lastContactMessageAt(conversation)).as("forward only").isEqualTo(later);
    }

    @Test
    void P1_aDuplicateLeavesAClosedConversationClosedAndItsActivityAsItWas() {
        Business business = business();
        UUID row = store(WhatsAppPayloads.inbound(business.number()));
        run(business.tenant(), row);
        UUID conversation = conversation(business.tenant(), contactId(business.tenant(), CUSTOMER))
                .id();
        close(conversation);
        admin.sql("update northstar.webhook_events set processed_at = null where id = :id")
                .param("id", row)
                .update();

        run(business.tenant(), row);

        assertThat(conversationStatus(conversation)).isEqualTo("closed");
        assertThat(latestActivityIsTheNewestMessage(conversation))
                .as("the backdated time set by close() stays")
                .isFalse();
    }

    // Types

    @Test
    void mediaAndLocationsAreStoredWithTheirCaptionOrNoBodyAndNothingIsDownloaded() {
        Business business = business();
        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number())
                        .typed("image", "{\"id\":\"media-123\",\"mime_type\":\"image/jpeg\",\"caption\":\"Venue\"}")));
        run(
                business.tenant(),
                store(WhatsAppPayloads.inbound(business.number())
                        .typed("location", "{\"latitude\":19.07,\"longitude\":72.87,\"name\":\"Studio\"}")));
        run(
                business.tenant(),
                store(
                        WhatsAppPayloads.inbound(business.number())
                                .typed(
                                        "interactive",
                                        "{\"type\":\"button_reply\",\"button_reply\":{\"id\":\"quote:1\",\"title\":\"Accept\"}}")));

        UUID conversation = conversation(business.tenant(), contactId(business.tenant(), CUSTOMER))
                .id();
        assertThat(messages(conversation))
                .extracting(stored -> stored.type() + "|" + stored.body())
                .containsExactlyInAnyOrder("image|Venue", "location|null", "interactive|Accept");
        assertThat(admin.sql(
                                "select count(*) from northstar.messages where conversation_id = :c and media_path is not null")
                        .param("c", conversation)
                        .query(Long.class)
                        .single())
                .as("no media is fetched or stored until step 4.5")
                .isZero();
    }

    // Status rows

    @Test
    void aStatusRowGoesToTheStatusPathAndWritesNoInboundRecords(CapturedOutput output) {
        Business business = business();
        String wamid = WhatsAppPayloads.wamid();
        intake.receive(WhatsAppPayloads.delivery()
                .status(business.number(), wamid, "delivered")
                .json()
                .getBytes(StandardCharsets.UTF_8));
        UUID row = webhookRow("status:" + wamid + ":delivered");

        run(business.tenant(), row);

        // No message has this wamid: the status waits for the sweep (step 2.7, WhatsAppStatusTests has the rest).
        assertThat(processed(row)).isFalse();
        assertThat(error(row)).isEqualTo("message_not_found_yet");
        assertThat(output.getAll()).contains("webhook.status_waiting provider=whatsapp webhook_event_id=" + row);
        assertThat(totals(business.tenant()))
                .as("a status creates no contact, source, conversation, message, lead or event")
                .isEqualTo(new Totals(0, 0, 0, 0, 0, 0));
    }

    // What is recorded and logged

    @Test
    void engineEventsHoldIdsCodesAndTheTimestampOnly() {
        Business business = business();
        String marker = WhatsAppPayloads.marker();
        String profile = "Profile " + UUID.randomUUID();
        String waId = randomWaId();
        Inbound message = WhatsAppPayloads.inbound(business.number())
                .from(waId)
                .profileName(profile)
                .text(marker);
        run(business.tenant(), store(message));

        EventRow event = engineEvent(business.tenant(), message.wamid());
        for (String personal : List.of(marker, profile, waId, "+" + waId, message.wamid(), business.number())) {
            assertThat(event.payload()).as("the payload holds no message data").doesNotContain(personal);
        }
        JsonNode payload = JSON.readTree(event.payload());
        List<String> keys = payload.properties().stream().map(Map.Entry::getKey).toList();
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "webhook_event_id",
                        "contact_id",
                        "conversation_id",
                        "message_id",
                        "message_type",
                        "lead",
                        "sent_at");
        for (String id : List.of("webhook_event_id", "contact_id", "conversation_id", "message_id")) {
            assertThat(payload.path(id).stringValue()).matches(UUID_TEXT);
        }
    }

    @Test
    void processingLogsCarryIdsAndCodesOnly(CapturedOutput output) {
        Business business = business();
        UUID unpublished = data.tenant();
        String unpublishedNumber = data.whatsAppNumber(unpublished);
        String marker = WhatsAppPayloads.marker();
        String profile = "Profile " + UUID.randomUUID();
        String waId = randomWaId();
        String failingWaId = randomWaId();
        List<String> wamids = new ArrayList<>();

        // Processed, then replayed as a duplicate.
        Inbound processed = WhatsAppPayloads.inbound(business.number())
                .from(waId)
                .profileName(profile)
                .text(marker);
        wamids.add(processed.wamid());
        UUID processedRow = store(processed);
        run(business.tenant(), processedRow);
        admin.sql("update northstar.webhook_events set processed_at = null where id = :id")
                .param("id", processedRow)
                .update();
        run(business.tenant(), processedRow);
        // A permanent failure.
        Inbound invalid =
                WhatsAppPayloads.inbound(business.number()).from("0" + waId).text(marker);
        wamids.add(invalid.wamid());
        run(business.tenant(), store(invalid));
        // A retryable failure.
        Inbound failing = WhatsAppPayloads.inbound(business.number())
                .from(failingWaId)
                .profileName(profile)
                .text(marker);
        wamids.add(failing.wamid());
        UUID failingRow = store(failing);
        failLeadInsertsFor(business.tenant());
        try {
            run(business.tenant(), failingRow);
        } finally {
            stopFailingLeadInserts();
        }
        // No published workflow.
        Inbound noWorkflow = WhatsAppPayloads.inbound(unpublishedNumber)
                .from(waId)
                .profileName(profile)
                .text(marker);
        wamids.add(noWorkflow.wamid());
        run(unpublished, store(noWorkflow));

        String logs = output.getAll();
        assertThat(logs)
                .contains("webhook.processed")
                .contains("webhook.duplicate_message")
                .contains("error=contact_phone_invalid final=true")
                .contains("error=database_conflict final=false")
                .contains("webhook.no_published_workflow");
        List<String> secrets = new ArrayList<>(List.of(
                marker,
                profile,
                waId,
                "+" + waId,
                failingWaId,
                "+" + failingWaId,
                business.number(),
                unpublishedNumber,
                FAILURE_MARKER));
        secrets.addAll(wamids);
        for (String secret : secrets) {
            assertThat(logs).as("logs must not contain it").doesNotContain(secret);
        }
    }

    // The write services

    @Test
    void theWriteServicesJoinTheJobsTransactionAndActOnlyAsTheSystem() {
        UUID tenant = data.tenant();
        assertThatThrownBy(() -> SystemScope.call(tenant, () -> contacts.hasInboundMessage("wamid.TEST")))
                .as("no transaction of their own")
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> SystemScope.call(tenant, leadIntake::publishedWorkflowVersion))
                .isInstanceOf(IllegalTransactionStateException.class);

        TestData.Member member = data.member("admin");
        TenantContext adminContext = new TenantContext(member.tenantId(), member.userId(), Role.ADMIN);
        assertThatThrownBy(() -> TenantScope.call(
                        adminContext, () -> transactions.execute(status -> contacts.hasInboundMessage("wamid.TEST"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");
        assertThatThrownBy(() -> TenantScope.call(
                        adminContext, () -> transactions.execute(status -> leadIntake.findOpenLead(UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");
        assertThatThrownBy(() -> TenantScope.call(
                        adminContext,
                        () -> transactions.execute(
                                status -> engineEvents.recordMessageReceived(new EngineEvents.MessageReceived(
                                        "wamid.TEST",
                                        null,
                                        EngineEvents.LeadOutcome.NONE,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "text",
                                        Instant.now())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");
    }

    // Helpers

    /** A business with a WhatsApp number and one published workflow. */
    private record Business(UUID tenant, String number, UUID version) {}

    private Business business() {
        UUID tenant = data.tenant();
        return new Business(tenant, data.whatsAppNumber(tenant), data.workflowVersion(tenant));
    }

    /** Stores the message through the real intake (step 2.2), as a verified delivery would be, and returns its row. */
    private UUID store(Inbound message) {
        intake.receive(message.json().getBytes(StandardCharsets.UTF_8));
        return webhookRow("message:" + message.wamid());
    }

    private UUID webhookRow(String providerEventId) {
        return admin.sql(
                        "select id from northstar.webhook_events where provider = 'whatsapp' and provider_event_id = :e")
                .param("e", providerEventId)
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
     * Makes every lead insert in this tenant fail with a serialization failure (SQLSTATE 40001), through a test-only
     * trigger created as migrator, the tables' owner. Always paired with {@link #stopFailingLeadInserts()}.
     */
    private void failLeadInsertsFor(UUID tenant) {
        asMigrator(
                """
                create function northstar.test_fail_lead_insert() returns trigger language plpgsql as $$
                begin
                  raise exception '%s' using errcode = '40001';
                end
                $$""".formatted(FAILURE_MARKER),
                "create trigger test_fail_lead_insert before insert on northstar.leads for each row"
                        + " when (new.tenant_id = '" + tenant
                        + "') execute function northstar.test_fail_lead_insert()");
    }

    private void stopFailingLeadInserts() {
        asMigrator(
                "drop trigger if exists test_fail_lead_insert on northstar.leads",
                "drop function if exists northstar.test_fail_lead_insert()");
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

    private boolean processed(UUID row) {
        return processedAt(row) != null;
    }

    private OffsetDateTime processedAt(UUID row) {
        return nullable(admin.sql("select processed_at from northstar.webhook_events where id = :id")
                .param("id", row)
                .query((rs, i) -> rs.getObject("processed_at", OffsetDateTime.class))
                .list());
    }

    private String error(UUID row) {
        return nullable(admin.sql("select error from northstar.webhook_events where id = :id")
                .param("id", row)
                .query((rs, i) -> rs.getString("error"))
                .list());
    }

    /** The one row's value, which may be null (JdbcClient's single() refuses a null). */
    private static <T> T nullable(List<T> values) {
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    private UUID contactId(UUID tenant, String phone) {
        return admin.sql("select id from northstar.contacts where tenant_id = :t and normalized_phone = :p")
                .param("t", tenant)
                .param("p", phone)
                .query(UUID.class)
                .single();
    }

    private String contactName(UUID contact) {
        return nullable(admin.sql("select name from northstar.contacts where id = :id")
                .param("id", contact)
                .query((rs, i) -> rs.getString("name"))
                .list());
    }

    private record ConversationRow(
            UUID id, String number, UUID assignee, OffsetDateTime lastInboundAt, OffsetDateTime windowExpiresAt) {}

    private ConversationRow conversation(UUID tenant, UUID contact) {
        return admin.sql("""
                        select id, whatsapp_phone_number_id, assigned_membership_id, last_inbound_at, window_expires_at
                        from northstar.conversations where tenant_id = :t and contact_id = :c""")
                .param("t", tenant)
                .param("c", contact)
                .query((rs, i) -> new ConversationRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("whatsapp_phone_number_id"),
                        rs.getObject("assigned_membership_id", UUID.class),
                        rs.getObject("last_inbound_at", OffsetDateTime.class),
                        rs.getObject("window_expires_at", OffsetDateTime.class)))
                .single();
    }

    private Instant lastContactMessageAt(UUID conversation) {
        return admin.sql("select last_contact_message_at from northstar.conversations where id = :id")
                .param("id", conversation)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /** Closes the conversation and backdates its latest activity, as an operator could today (no step closes one). */
    private void close(UUID conversation) {
        admin.sql("""
                        update northstar.conversations set status = 'closed', last_message_at = now() - interval '1 day'
                        where id = :id""").param("id", conversation).update();
    }

    private String conversationStatus(UUID conversation) {
        return admin.sql("select status from northstar.conversations where id = :id")
                .param("id", conversation)
                .query(String.class)
                .single();
    }

    /** Whether the conversation's latest activity is the time its newest message was stored. */
    private boolean latestActivityIsTheNewestMessage(UUID conversation) {
        return Boolean.TRUE.equals(
                admin.sql("""
                        select c.last_message_at = (select max(created_at) from northstar.messages m
                                                    where m.conversation_id = c.id)
                        from northstar.conversations c where c.id = :id""").param("id", conversation).query(Boolean.class).single());
    }

    private record MessageRow(
            UUID id,
            String direction,
            String senderType,
            String wamid,
            String type,
            String body,
            OffsetDateTime sentAt,
            String status) {}

    private List<MessageRow> messages(UUID conversation) {
        return admin.sql("""
                        select id, direction, sender_type, provider_message_id, type, body, sent_at, status
                        from northstar.messages where conversation_id = :c order by created_at, id""")
                .param("c", conversation)
                .query((rs, i) -> new MessageRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("direction"),
                        rs.getString("sender_type"),
                        rs.getString("provider_message_id"),
                        rs.getString("type"),
                        rs.getString("body"),
                        rs.getObject("sent_at", OffsetDateTime.class),
                        rs.getString("status")))
                .list();
    }

    private record LeadRow(
            UUID id,
            UUID conversationId,
            UUID workflowVersionId,
            String stage,
            String origin,
            UUID assignee,
            int version,
            boolean closed) {}

    private List<LeadRow> leads(UUID tenant, UUID contact) {
        return admin.sql("""
                        select id, conversation_id, workflow_version_id, stage, origin, assigned_membership_id, version,
                               closed_at is not null as closed
                        from northstar.leads where tenant_id = :t and contact_id = :c order by created_at, id""")
                .param("t", tenant)
                .param("c", contact)
                .query((rs, i) -> new LeadRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("workflow_version_id", UUID.class),
                        rs.getString("stage"),
                        rs.getString("origin"),
                        rs.getObject("assigned_membership_id", UUID.class),
                        rs.getInt("version"),
                        rs.getBoolean("closed")))
                .list();
    }

    private LeadRow onlyLead(UUID tenant, UUID contact) {
        List<LeadRow> leads = leads(tenant, contact);
        assertThat(leads).hasSize(1);
        return leads.getFirst();
    }

    private record EventRow(UUID id, UUID leadId, String type, String dedupeKey, String payload, boolean processed) {}

    private EventRow engineEvent(UUID tenant, String wamid) {
        return admin.sql("""
                        select id, lead_id, type, dedupe_key, payload::text as payload, processed_at is not null as done
                        from northstar.events where tenant_id = :t and dedupe_key = :k""")
                .param("t", tenant)
                .param("k", "message.received:" + wamid)
                .query((rs, i) -> new EventRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("lead_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("dedupe_key"),
                        rs.getString("payload"),
                        rs.getBoolean("done")))
                .single();
    }

    private List<String> eventKeys(UUID tenant) {
        return admin.sql("select dedupe_key from northstar.events where tenant_id = :t")
                .param("t", tenant)
                .query(String.class)
                .list();
    }

    private static final Set<String> COUNTED =
            Set.of("contacts", "contact_sources", "conversations", "messages", "leads", "events");

    private long count(String table, UUID tenant) {
        if (!COUNTED.contains(table)) throw new IllegalArgumentException(table);
        return admin.sql("select count(*) from northstar." + table + " where tenant_id = :t")
                .param("t", tenant)
                .query(Long.class)
                .single();
    }

    /** Rows of each table the job writes, in one tenant. */
    private record Totals(
            long contacts, long contactSources, long conversations, long messages, long leads, long events) {}

    private Totals totals(UUID tenant) {
        return new Totals(
                count("contacts", tenant),
                count("contact_sources", tenant),
                count("conversations", tenant),
                count("messages", tenant),
                count("leads", tenant),
                count("events", tenant));
    }
}
