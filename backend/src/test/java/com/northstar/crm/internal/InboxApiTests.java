package com.northstar.crm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.contacts.ConversationInbox;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The inbox's reads (step 2.9a) through the real login path, against Postgres with RLS on: who sees which conversation
 * (P9, P11), the contact's consent for the Opted out badge (W32), newest-first paging by cursor, and messages as the
 * inbox shows them (a read message as delivered too, a failure with its code, a template message's text).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InboxApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final ConversationInbox inbox;
    private final TestData data;
    private final JdbcClient admin;

    InboxApiTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired ConversationInbox inbox,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.inbox = inbox;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    // Who sees what

    @Test
    void anAdminSeesEveryConversationOfTheBusinessNewestActivityFirst() {
        Team team = team();
        UUID older = conversation(team, team.staffMembership(), T0);
        UUID unassigned = conversation(team, null, T0.plusSeconds(60));
        UUID newest = conversation(team, team.otherStaffMembership(), T0.plusSeconds(120));
        conversation(team(), null, T0.plusSeconds(180)); // another business's, never listed

        JsonNode page = json(get(team.adminUser(), "/api/v1/conversations"));

        assertThat(ids(page)).containsExactly(newest, unassigned, older);
        assertThat(page.get("nextCursor").isNull()).isTrue();
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("status").stringValue()).isEqualTo("open");
        assertThat(first.get("assignedMembershipId").stringValue())
                .isEqualTo(team.otherStaffMembership().toString());
        assertThat(first.get("automationPaused").booleanValue()).isFalse();
        assertThat(first.get("needsHuman").booleanValue()).isFalse();
        assertThat(first.get("lastMessageAt").stringValue()).isEqualTo("2026-11-22T10:02:00Z");
        assertThat(first.get("lastInboundAt").isNull()).isTrue();
        assertThat(first.get("windowExpiresAt").isNull())
                .as("no inbound message, no window")
                .isTrue();
        assertThat(first.get("contact").get("consentState").stringValue()).isEqualTo("unknown");
        assertThat(first.get("contact").get("optedOutAt").isNull()).isTrue();
    }

    @Test
    void W32_anOptedOutContactCarriesItsConsentStateForTheOptedOutBadge() {
        Team team = team();
        UUID contact = data.contact(team.tenant(), "+919800000101", "Asha Rao");
        UUID conversation = data.conversation(team.tenant(), contact, data.whatsAppNumber(team.tenant()), null);
        admin.sql("""
                        update northstar.contacts set consent_state = 'opted_out', opt_out_at = :at where id = :id""").param("at", utc(T0)).param("id", contact).update();
        admin.sql("""
                        update northstar.conversations set last_inbound_at = :at where id = :id""").param("at", utc(T0)).param("id", conversation).update();

        JsonNode listed = json(get(team.adminUser(), "/api/v1/conversations"))
                .get("items")
                .get(0);
        JsonNode opened = json(get(team.adminUser(), "/api/v1/conversations/" + conversation));

        for (JsonNode view : List.of(listed, opened)) {
            JsonNode shown = view.get("contact");
            assertThat(shown.get("id").stringValue()).isEqualTo(contact.toString());
            assertThat(shown.get("name").stringValue()).isEqualTo("Asha Rao");
            assertThat(shown.get("phone").stringValue()).isEqualTo("+919800000101");
            assertThat(shown.get("consentState").stringValue()).isEqualTo("opted_out");
            assertThat(shown.get("optedOutAt").stringValue()).isEqualTo("2026-11-22T10:00:00Z");
            assertThat(view.get("lastInboundAt").stringValue()).isEqualTo("2026-11-22T10:00:00Z");
            assertThat(view.get("windowExpiresAt").stringValue()).isEqualTo("2026-11-23T10:00:00Z");
        }
    }

    @Test
    void P11_staffListAndOpenOnlyTheConversationsAssignedToThem(CapturedOutput output) throws Exception {
        Team team = team();
        UUID mine = conversation(team, team.staffMembership(), T0);
        UUID colleagues = conversation(team, team.otherStaffMembership(), T0.plusSeconds(60));
        UUID unassigned = conversation(team, null, T0.plusSeconds(120));
        data.message(team.tenant(), mine);
        data.message(team.tenant(), colleagues);

        assertThat(ids(json(get(team.staffUser(), "/api/v1/conversations")))).containsExactly(mine);
        assertThat(get(team.staffUser(), "/api/v1/conversations/" + mine)).hasStatusOk();
        assertThat(json(get(team.staffUser(), "/api/v1/conversations/" + mine + "/messages"))
                        .get("items"))
                .hasSize(1);

        String missingBody = body(get(team.staffUser(), "/api/v1/conversations/" + UUID.randomUUID()));
        String missingMessagesBody =
                body(get(team.staffUser(), "/api/v1/conversations/" + UUID.randomUUID() + "/messages"));
        for (UUID hidden : List.of(colleagues, unassigned)) {
            MvcTestResult opened = get(team.staffUser(), "/api/v1/conversations/" + hidden);
            MvcTestResult messages = get(team.staffUser(), "/api/v1/conversations/" + hidden + "/messages");
            assertThat(opened).hasStatus(404);
            assertThat(messages).hasStatus(404);
            assertThat(body(opened))
                    .as("the same 404 as a missing conversation")
                    .isEqualTo(missingBody);
            assertThat(body(messages)).isEqualTo(missingMessagesBody);
            assertThat(output.getAll()).contains("Not found: conversation " + hidden);
        }
        assertThat(ids(json(get(team.adminUser(), "/api/v1/conversations"))))
                .as("the admin sees all three")
                .containsExactly(unassigned, colleagues, mine);
    }

    @Test
    void P9_anotherTenantsConversationIsNeverListedAndIsTheSame404AsAMissingOne() throws Exception {
        Team ours = team();
        Team theirs = team();
        UUID their = conversation(theirs, null, T0);
        data.message(theirs.tenant(), their);

        assertThat(json(get(ours.adminUser(), "/api/v1/conversations")).get("items"))
                .isEmpty();
        for (String path : List.of("", "/messages")) {
            MvcTestResult foreign = get(ours.adminUser(), "/api/v1/conversations/" + their + path);
            MvcTestResult missing = get(ours.adminUser(), "/api/v1/conversations/" + UUID.randomUUID() + path);
            assertThat(foreign).hasStatus(404);
            assertThat(body(foreign)).isEqualTo(body(missing));
        }
    }

    // Paging

    @Test
    void pagesWalkTheListWithoutGapsOrRepeatsAndTiesAreBrokenById() {
        Team team = team();
        List<UUID> expected = new ArrayList<>();
        expected.add(conversation(team, null, T0.plusSeconds(300)));
        // Two at the same instant: the one with the higher ID comes first.
        UUID tieA = conversation(team, null, T0.plusSeconds(200));
        UUID tieB = conversation(team, null, T0.plusSeconds(200));
        expected.addAll(tieA.toString().compareTo(tieB.toString()) > 0 ? List.of(tieA, tieB) : List.of(tieB, tieA));
        expected.add(conversation(team, null, T0.plusSeconds(100)));
        expected.add(conversation(team, null, T0));

        List<UUID> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = json(get(
                    team.adminUser(), "/api/v1/conversations?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
            assertThat(page.get("items").size()).isLessThanOrEqualTo(2);
            walked.addAll(ids(page));
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").stringValue();
            pages++;
        } while (cursor != null);

        assertThat(walked).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void aConversationWithNewActivityMovesToTheTopAndALaterPageNoLongerListsIt() {
        Team team = team();
        UUID newest = conversation(team, null, T0.plusSeconds(200));
        UUID middle = conversation(team, null, T0.plusSeconds(100));
        UUID oldest = conversation(team, null, T0);
        JsonNode first = json(get(team.adminUser(), "/api/v1/conversations?limit=1"));
        assertThat(ids(first)).containsExactly(newest);

        setLastMessageAt(oldest, T0.plusSeconds(500));

        JsonNode second = json(get(
                team.adminUser(),
                "/api/v1/conversations?limit=5&cursor="
                        + first.get("nextCursor").stringValue()));
        assertThat(ids(second)).containsExactly(middle);
        assertThat(ids(json(get(team.adminUser(), "/api/v1/conversations"))))
                .as("reloading the first page shows it")
                .containsExactly(oldest, newest, middle);
    }

    @Test
    void aCursorOnlyWorksForTheListThatIssuedItAndBadPagesAre400() {
        Team team = team();
        UUID one = conversation(team, null, T0);
        UUID two = conversation(team, null, T0.plusSeconds(10));
        for (int i = 0; i < 3; i++) outbound(team, one, "sent", T0.plusSeconds(i));
        outbound(team, two, "sent", T0);
        String messagesCursor = json(get(team.adminUser(), "/api/v1/conversations/" + one + "/messages?limit=1"))
                .get("nextCursor")
                .stringValue();
        String conversationsCursor = json(get(team.adminUser(), "/api/v1/conversations?limit=1"))
                .get("nextCursor")
                .stringValue();

        assertThat(get(team.adminUser(), "/api/v1/conversations/" + one + "/messages?cursor=" + messagesCursor))
                .hasStatusOk();
        for (String uri : List.of(
                "/api/v1/conversations/" + two + "/messages?cursor=" + messagesCursor,
                "/api/v1/conversations/" + one + "/messages?cursor=" + conversationsCursor,
                "/api/v1/conversations?cursor=" + messagesCursor,
                "/api/v1/conversations?cursor=not-a-cursor",
                "/api/v1/conversations?cursor=" + messagesCursor.substring(1))) {
            assertThat(get(team.adminUser(), uri))
                    .as(uri)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid_cursor");
        }
        for (String uri : List.of(
                "/api/v1/conversations?limit=0",
                "/api/v1/conversations?limit=101",
                "/api/v1/conversations/" + one + "/messages?limit=0")) {
            assertThat(get(team.adminUser(), uri))
                    .as(uri)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("validation_failed");
        }
    }

    // Messages

    @Test
    void messagesAreNewestFirstAndShowDeliveryAsTheInboxNeedsIt() {
        Team team = team();
        UUID conversation = conversation(team, team.staffMembership(), T0);
        UUID template = data.messageTemplate(team.tenant());
        UUID inbound = insertMessage(
                team,
                conversation,
                T0,
                "'in', 'contact', null, 'wamid.IN1', 'text', 'Need a shoot', null, null, null, :at, null, null");
        UUID readWithoutDelivered = insertMessage(
                team,
                conversation,
                T0.plusSeconds(1),
                "'out', 'ai', null, 'wamid.OUT1', 'text', 'Which date?', null, 'read', null, :at, null, :at");
        UUID fromStaff = insertMessage(
                team,
                conversation,
                T0.plusSeconds(2),
                "'out', 'staff', :staff, 'wamid.OUT2', 'text', 'On it', null, 'delivered', null, :at, :at, null");
        UUID failedBeforeMeta = insertMessage(
                team,
                conversation,
                T0.plusSeconds(3),
                "'out', 'system', null, null, 'text', 'Late', null, 'failed', 'window_closed_no_template', null,"
                        + " null, null");
        UUID failedByMeta = insertMessage(
                team,
                conversation,
                T0.plusSeconds(4),
                "'out', 'system', null, 'wamid.OUT3', 'text', 'Undelivered', null, 'failed', 'meta_131026', :at,"
                        + " null, null");
        UUID templateMessage = insertMessage(
                team,
                conversation,
                T0.plusSeconds(5),
                "'out', 'system', null, null, 'template', 'Hello Asha', :template, 'queued', null, null, null, null",
                template);

        JsonNode page = json(get(team.staffUser(), "/api/v1/conversations/" + conversation + "/messages"));
        assertThat(ids(page))
                .containsExactly(
                        templateMessage, failedByMeta, failedBeforeMeta, fromStaff, readWithoutDelivered, inbound);
        JsonNode items = page.get("items");

        JsonNode tpl = items.get(0);
        assertThat(tpl.get("type").stringValue()).isEqualTo("template");
        assertThat(tpl.get("body").stringValue())
                .as("the text rendered when queued")
                .isEqualTo("Hello Asha");
        assertThat(tpl.get("template").get("id").stringValue()).isEqualTo(template.toString());
        assertThat(tpl.get("template").get("language").stringValue()).isEqualTo("en");
        assertThat(tpl.get("status").stringValue()).isEqualTo("queued");
        assertThat(tpl.get("delivered").booleanValue()).isFalse();

        assertThat(items.get(1).get("status").stringValue()).isEqualTo("failed");
        assertThat(items.get(1).get("errorCode").stringValue())
                .as("a failure Meta reported after accepting the send")
                .isEqualTo("meta_131026");
        assertThat(items.get(2).get("errorCode").stringValue()).isEqualTo("window_closed_no_template");
        assertThat(items.get(2).get("sentAt").isNull()).isTrue();

        JsonNode staff = items.get(3);
        assertThat(staff.get("senderType").stringValue()).isEqualTo("staff");
        assertThat(staff.get("senderMembershipId").stringValue())
                .isEqualTo(team.staffMembership().toString());
        assertThat(staff.get("delivered").booleanValue()).isTrue();

        JsonNode read = items.get(4);
        assertThat(read.get("status").stringValue()).isEqualTo("read");
        assertThat(read.get("delivered").booleanValue())
                .as("read without delivered still shows as delivered")
                .isTrue();
        assertThat(read.get("deliveredAt").isNull()).isTrue();
        assertThat(read.get("readAt").stringValue()).isEqualTo("2026-11-22T10:00:01Z");
        assertThat(read.get("template").isNull()).isTrue();

        JsonNode in = items.get(5);
        assertThat(in.get("direction").stringValue()).isEqualTo("in");
        assertThat(in.get("senderType").stringValue()).isEqualTo("contact");
        assertThat(in.get("body").stringValue()).isEqualTo("Need a shoot");
        assertThat(in.get("status").isNull())
                .as("inbound messages have no status")
                .isTrue();
        assertThat(in.get("delivered").booleanValue()).isFalse();
        assertThat(in.get("createdAt").stringValue()).isEqualTo("2026-11-22T10:00:00Z");
        assertThat(in.get("conversationId").stringValue()).isEqualTo(conversation.toString());
    }

    @Test
    void messagesPageNewestFirstByCursor() {
        Team team = team();
        UUID conversation = conversation(team, null, T0);
        List<UUID> newestFirst = new ArrayList<>();
        for (int i = 0; i < 5; i++) newestFirst.addFirst(outbound(team, conversation, "sent", T0.plusSeconds(i)));

        List<UUID> walked = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(get(
                    team.adminUser(),
                    "/api/v1/conversations/" + conversation + "/messages?limit=2"
                            + (cursor == null ? "" : "&cursor=" + cursor)));
            walked.addAll(ids(page));
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").stringValue();
        } while (cursor != null);

        assertThat(walked).containsExactlyElementsOf(newestFirst);
    }

    // Context and logs

    @Test
    void P10_theInboxRefusesNoContextAndTheSystemActor() {
        UUID tenant = data.tenant();
        assertThatThrownBy(() -> inbox.conversations(null, 10)).isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> SystemScope.call(tenant, () -> inbox.conversations(null, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admins and staff");
    }

    @Test
    void textNamesAndPhoneNumbersNeverReachTheLogs(CapturedOutput output) {
        Team team = team();
        UUID contact = data.contact(team.tenant(), "+919800000202", "Ravi Kumar");
        UUID conversation = data.conversation(team.tenant(), contact, data.whatsAppNumber(team.tenant()), null);
        insertMessage(
                team,
                conversation,
                T0,
                "'in', 'contact', null, :wamid, 'text', 'Private shoot details', null, null, null, :at, null, null");

        assertThat(get(team.adminUser(), "/api/v1/conversations")).hasStatusOk();
        assertThat(get(team.adminUser(), "/api/v1/conversations/" + conversation))
                .hasStatusOk();
        assertThat(get(team.adminUser(), "/api/v1/conversations/" + conversation + "/messages"))
                .hasStatusOk();
        assertThat(get(team.staffUser(), "/api/v1/conversations/" + conversation + "/messages"))
                .hasStatus(404);

        assertThat(output.getAll()).doesNotContain("Private shoot details", "Ravi Kumar", "9800000202");
    }

    // Helpers.

    /** A business with an admin and two staff members. */
    private record Team(
            UUID tenant,
            UUID adminUser,
            UUID staffUser,
            UUID staffMembership,
            UUID otherStaffUser,
            UUID otherStaffMembership) {}

    private Team team() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID otherUser = data.user();
        UUID otherMembership = data.membership(owner.tenantId(), otherUser, "staff", "active");
        return new Team(owner.tenantId(), owner.userId(), staffUser, staffMembership, otherUser, otherMembership);
    }

    /** A conversation for a new contact, assigned to the membership (null: unassigned), last active at this time. */
    private UUID conversation(Team team, UUID assignee, Instant lastMessageAt) {
        UUID contact = data.contact(team.tenant());
        UUID conversation = data.conversation(team.tenant(), contact, data.whatsAppNumber(team.tenant()), assignee);
        setLastMessageAt(conversation, lastMessageAt);
        return conversation;
    }

    private void setLastMessageAt(UUID conversation, Instant at) {
        admin.sql("update northstar.conversations set last_message_at = :at where id = :id")
                .param("at", utc(at))
                .param("id", conversation)
                .update();
    }

    /** An outbound text message Meta accepted, created at this time. */
    private UUID outbound(Team team, UUID conversation, String status, Instant at) {
        return insertMessage(
                team,
                conversation,
                at,
                "'out', 'system', null, :wamid, 'text', 'Fixture', null, '" + status + "', null, :at, null, null");
    }

    /**
     * A message created at this time, with these values for direction, sender_type, sender_membership_id,
     * provider_message_id, type, body, template_id, status, error_code, sent_at, delivered_at and read_at. The values
     * may use :at (the time), :staff (the team's staff membership), :wamid (a random wamid) and :template.
     */
    private UUID insertMessage(Team team, UUID conversation, Instant at, String values) {
        return insertMessage(team, conversation, at, values, null);
    }

    private UUID insertMessage(Team team, UUID conversation, Instant at, String values, UUID template) {
        String sql = """
                insert into northstar.messages
                  (tenant_id, conversation_id, created_at, direction, sender_type, sender_membership_id,
                   provider_message_id, type, body, template_id, status, error_code, sent_at, delivered_at, read_at)
                values (:tenant, :conversation, :at, %s) returning id""".formatted(values);
        JdbcClient.StatementSpec insert = admin.sql(sql)
                .param("tenant", team.tenant())
                .param("conversation", conversation)
                .param("at", utc(at));
        if (sql.contains(":staff")) insert = insert.param("staff", team.staffMembership());
        if (sql.contains(":wamid")) insert = insert.param("wamid", "wamid.TEST" + UUID.randomUUID());
        if (sql.contains(":template")) insert = insert.param("template", template);
        return insert.query(UUID.class).single();
    }

    private MvcTestResult get(UUID user, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, "inbox-test")
                .exchange();
    }

    private static JsonNode json(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return JSON.readTree(body(result));
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<UUID> ids(JsonNode page) {
        return page.get("items")
                .valueStream()
                .map(item -> UUID.fromString(item.get("id").stringValue()))
                .toList();
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
