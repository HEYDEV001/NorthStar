package com.northstar.crm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.contacts.ManualContacts;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.RequestKeys;
import com.northstar.workflow.LeadEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manual lead entry (step 2.10) through the real login path, against Postgres with RLS on: the phone forms accepted
 * and refused (P4), who the lead is assigned to, a returning customer's new lead (W2), what a staff member learns about
 * a number that already has an open lead (P11), another tenant's same number (P9), the Idempotency-Key (P21), and logs
 * with no phone numbers, names, emails or keys.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LeadEntryApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST_ID = "lead-entry-test";

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final AppUserDatabase app;
    private final ManualContacts contacts;
    private final LeadEntry leads;
    private final RequestKeys requestKeys;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    LeadEntryApiTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired AppUserDatabase app,
            @Autowired ManualContacts contacts,
            @Autowired LeadEntry leads,
            @Autowired RequestKeys requestKeys,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.app = app;
        this.contacts = contacts;
        this.leads = leads;
        this.requestKeys = requestKeys;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    // A new number

    @Test
    void anAdminEntersALeadForANewNumberAndItIsTheirOwnInNew() {
        Team team = team();
        String phone = phone();

        MvcTestResult result = enter(
                team.adminUser(),
                request(national(phone)).put("name", "  Asha \t Rao ").put("email", "asha@example.com"));

        assertThat(result).hasStatus(201);
        JsonNode lead = json(result);
        UUID leadId = id(lead);
        assertThat(lead.get("stage").stringValue()).isEqualTo("new");
        assertThat(lead.get("origin").stringValue()).isEqualTo("manual");
        assertThat(lead.get("workflowVersionId").stringValue())
                .isEqualTo(team.version().toString());
        assertThat(lead.get("assignedMembershipId").stringValue())
                .isEqualTo(team.adminMembership().toString());
        assertThat(lead.get("conversationId").isNull())
                .as("no conversation until a message exists")
                .isTrue();
        assertThat(lead.get("createdAt").stringValue()).endsWith("Z");
        JsonNode contact = lead.get("contact");
        UUID contactId = UUID.fromString(contact.get("id").stringValue());
        assertThat(contact.get("phone").stringValue()).isEqualTo(phone);
        assertThat(contact.get("name").stringValue()).isEqualTo("Asha Rao");
        assertThat(contact.get("email").stringValue()).isEqualTo("asha@example.com");
        assertThat(contact.get("consentState").stringValue())
                .as("entering a number is not an opt-in")
                .isEqualTo("unknown");
        assertThat(contact.get("optedOutAt").isNull()).isTrue();

        assertThat(leadsOf(contactId)).containsExactly("new|manual|" + team.adminMembership() + "||open");
        assertThat(sourcesOf(contactId)).containsExactly("manual");
        assertThat(count("select count(*) from northstar.conversations where contact_id = :id", contactId))
                .isZero();
        assertThat(admin.sql(
                                "select consent_state || '|' || (opted_in_at is null) from northstar.contacts where id = :id")
                        .param("id", contactId)
                        .query(String.class)
                        .single())
                .isEqualTo("unknown|true");
        assertThat(audit(team.tenant()))
                .containsExactlyInAnyOrder(
                        "contact.created|contacts|" + contactId + "|" + team.adminUser(),
                        "lead.created|leads|" + leadId + "|" + team.adminUser());
        assertThat(count("select count(*) from northstar.events where lead_id = :id", leadId))
                .as("no staff.action kind is a lead's creation")
                .isZero();
        assertThat(requestKeysOf(team.tenant()))
                .containsExactly("lead.create|" + team.adminMembership() + "|" + leadId);
    }

    @Test
    void aStaffMembersLeadIsAlwaysTheirOwn() {
        Team team = team();

        JsonNode unnamed = json(enter(team.staffUser(), request(phone())), 201);
        JsonNode named = json(
                enter(
                        team.staffUser(),
                        request(phone())
                                .put(
                                        "assignedMembershipId",
                                        team.staffMembership().toString())),
                201);
        String phone = phone();
        MvcTestResult someoneElse = enter(
                team.staffUser(),
                request(phone)
                        .put("assignedMembershipId", team.colleagueMembership().toString()));

        assertThat(unnamed.get("assignedMembershipId").stringValue())
                .isEqualTo(team.staffMembership().toString());
        assertThat(named.get("assignedMembershipId").stringValue())
                .isEqualTo(team.staffMembership().toString());
        assertThat(someoneElse).hasStatus(403);
        assertThat(code(someoneElse)).isEqualTo("assignee_not_allowed");
        assertThat(contactsWithPhone(team.tenant(), phone)).isZero();
        assertThat(staffSee(team, "select count(*) from northstar.contacts where id = :id", contactId(unnamed)))
                .as("the new contact is visible to them through their lead")
                .isOne();
    }

    @Test
    void anAdminAssignsTheLeadToAnyActiveMember() {
        Team team = team();

        JsonNode toStaff = json(
                enter(
                        team.adminUser(),
                        request(phone())
                                .put(
                                        "assignedMembershipId",
                                        team.staffMembership().toString())),
                201);
        UUID otherAdmin = data.membership(team.tenant(), data.user(), "admin", "active");
        JsonNode toAdmin =
                json(enter(team.adminUser(), request(phone()).put("assignedMembershipId", otherAdmin.toString())), 201);

        assertThat(toStaff.get("assignedMembershipId").stringValue())
                .isEqualTo(team.staffMembership().toString());
        assertThat(toAdmin.get("assignedMembershipId").stringValue()).isEqualTo(otherAdmin.toString());
        assertThat(staffSee(team, "select count(*) from northstar.leads where id = :id", id(toStaff)))
                .isOne();
        assertThat(staffSee(team, "select count(*) from northstar.contacts where id = :id", contactId(toStaff)))
                .isOne();
        assertThat(audit(team.tenant()))
                .as("the admin who entered it is the actor")
                .contains("lead.created|leads|" + id(toStaff) + "|" + team.adminUser());
        assertThat(firstTimelineRow(id(toStaff)))
                .as("its first timeline row (step 3.3) names the admin who entered it, not the assignee")
                .isEqualTo(
                        "stage_changed|staff|" + team.adminMembership() + "|||created|new||" + team.version() + "|0");
    }

    @Test
    void aStaffMembersManualLeadStartsItsTimelineInTheirName() {
        Team team = team();

        JsonNode lead = json(enter(team.staffUser(), request(phone())), 201);

        assertThat(firstTimelineRow(id(lead)))
                .as("— → New, by the member, with no event behind it (step 3.3)")
                .isEqualTo(
                        "stage_changed|staff|" + team.staffMembership() + "|||created|new||" + team.version() + "|0");
        assertThat(staffSee(team, "select count(*) from northstar.timeline_events where lead_id = :id", id(lead)))
                .as("their own lead's timeline")
                .isOne();
    }

    /** type|actor|membership|ref table|ref|summary|to|from and trigger (both null)|version|lead version, the only row. */
    private String firstTimelineRow(UUID lead) {
        return admin.sql("""
                        select type || '|' || actor_type || '|' || coalesce(actor_membership_id::text, '') || '|'
                               || coalesce(ref_table, '') || '|' || coalesce(ref_id::text, '') || '|' || summary
                               || '|' || (data->>'to') || '|' || coalesce(data->>'from', '')
                               || coalesce(data->>'trigger', '') || '|' || (data->>'workflow_version_id') || '|'
                               || (data->>'lead_version')
                        from northstar.timeline_events where lead_id = :lead""").param("lead", lead).query(String.class).single();
    }

    @Test
    void P9_anAssigneeThatIsNotAnActiveMemberOfTheBusinessIsRefusedAlike() {
        Team team = team();
        Team other = team();
        UUID deactivated = data.membership(team.tenant(), data.user(), "staff", "deactivated");
        UUID invited = data.membership(team.tenant(), data.user(), "staff", "invited");
        String phone = phone();

        List<String> bodies = new ArrayList<>();
        for (UUID assignee : List.of(deactivated, invited, other.staffMembership(), UUID.randomUUID())) {
            MvcTestResult result =
                    enter(team.adminUser(), request(phone).put("assignedMembershipId", assignee.toString()));
            assertThat(result).hasStatus(422);
            bodies.add(body(result));
        }

        assertThat(code(bodies.getFirst())).isEqualTo("assignee_not_active");
        assertThat(bodies)
                .as("another tenant's member and a missing one look the same")
                .containsOnly(bodies.getFirst());
        assertThat(contactsWithPhone(team.tenant(), phone)).isZero();
        assertThat(requestKeysOf(team.tenant())).isEmpty();
    }

    @Test
    void withoutOneSinglePublishedWorkflowTheEntryIs409AndStoresNothing() {
        TestData.Member owner = data.member("admin");
        String phone = phone();

        MvcTestResult none = enter(owner.userId(), request(phone));
        data.workflowVersion(owner.tenantId());
        data.workflowVersion(owner.tenantId());
        MvcTestResult two = enter(owner.userId(), request(phone));

        for (MvcTestResult result : List.of(none, two)) {
            assertThat(result).hasStatus(409);
            assertThat(code(result)).isEqualTo("no_published_workflow");
        }
        assertThat(contactsWithPhone(owner.tenantId(), phone)).isZero();
        assertThat(requestKeysOf(owner.tenantId())).isEmpty();
    }

    // P4

    @Test
    void P4_theFormsPeopleTypeAreStoredAsE164() {
        Team team = team();
        String mobile = phone();
        String national = national(mobile);
        String spaced = national.substring(0, 5) + " " + national.substring(5);

        for (String[] typed :
                List.of(new String[] {national, mobile}, new String[] {"0" + spaced, "+91" + national}, new String[] {
                    "+91-" + national.substring(0, 5) + "-" + national.substring(5), "+91" + national
                })) {
            // One number in three forms is one contact: the first creates it, the others find its open lead.
            MvcTestResult result = enter(team.adminUser(), request(typed[0]));
            assertThat(result.getResponse().getStatus()).isIn(201, 409);
        }
        assertThat(contactsWithPhone(team.tenant(), mobile)).isOne();

        String landline = "+9111" + (10_000_000 + (long) (Math.random() * 89_999_999L));
        String london = "+4420" + (10_000_000 + (long) (Math.random() * 89_999_999L));
        assertThat(json(enter(team.adminUser(), request(landline)), 201)
                        .get("contact")
                        .get("phone")
                        .stringValue())
                .as("a landline in E.164: +91 and 10 digits, any first digit but 0")
                .isEqualTo(landline);
        assertThat(json(enter(team.adminUser(), request(london)), 201)
                        .get("contact")
                        .get("phone")
                        .stringValue())
                .isEqualTo(london);
    }

    @Test
    void P4_anEntryWithoutAValidPhoneIs400AndStoresNothing() {
        Team team = team();
        long before = count("select count(*) from northstar.contacts where tenant_id = :id", team.tenant());

        List<ObjectNode> requests = new ArrayList<>();
        requests.add(JSON.createObjectNode().put("name", "No Phone"));
        requests.add(JSON.createObjectNode().putNull("phone"));
        for (String bad : List.of(
                "",
                "   ",
                "12345",
                "5876543210",
                "+91987654321",
                "+910987654321",
                "+919876543210 0",
                "+91 22 1234 5678",
                "+44 20 7946 0958",
                "(+91) 98765 43210",
                "98765.43210",
                "call me")) {
            requests.add(request(bad));
        }
        for (ObjectNode request : requests) {
            MvcTestResult result = enter(team.adminUser(), request);
            assertThat(result).as(request.toString()).hasStatus(400);
            assertThat(code(result)).isEqualTo("phone_invalid");
        }
        assertThat(count("select count(*) from northstar.contacts where tenant_id = :id", team.tenant()))
                .isEqualTo(before);
        assertThat(count("select count(*) from northstar.leads where tenant_id = :id", team.tenant()))
                .isZero();
        assertThat(requestKeysOf(team.tenant())).isEmpty();
    }

    @Test
    void otherFieldsAndTheKeyAreValidatedFirst() {
        Team team = team();
        String phone = phone();

        assertThat(code(enter(team.adminUser(), request(phone).put("email", "not-an-email"))))
                .isEqualTo("validation_failed");
        assertThat(code(enter(team.adminUser(), request(phone).put("email", "a".repeat(250) + "@x.in"))))
                .isEqualTo("validation_failed");
        assertThat(code(enter(team.adminUser(), request(phone).put("name", "Asha\u0000Rao"))))
                .isEqualTo("validation_failed");
        assertThat(code(enter(team.adminUser(), request(phone).put("name", "a".repeat(1001)))))
                .isEqualTo("validation_failed");
        assertThat(code(enter(team.adminUser(), request(phone).put("source", "manual"))))
                .as("an unknown field")
                .isEqualTo("malformed_request");
        assertThat(code(enter(team.adminUser(), request(phone).put("assignedMembershipId", "nobody"))))
                .isEqualTo("malformed_request");
        assertThat(code(enter(team.adminUser(), request(phone), null))).isEqualTo("idempotency_key_required");
        assertThat(code(enter(team.adminUser(), request(phone), "has spaces"))).isEqualTo("idempotency_key_invalid");
        assertThat(code(enter(team.adminUser(), request(phone), "k".repeat(101))))
                .isEqualTo("idempotency_key_invalid");
        assertThat(contactsWithPhone(team.tenant(), phone)).isZero();

        JsonNode empty =
                json(enter(team.adminUser(), request(phone).put("email", "").put("name", " \t ")), 201);
        assertThat(empty.get("contact").get("email").isNull())
                .as("an empty email is none")
                .isTrue();
        assertThat(empty.get("contact").get("name").isNull()).isTrue();
        String longName = "Ab ".repeat(300);
        JsonNode cut = json(enter(team.adminUser(), request(phone()).put("name", longName)), 201);
        assertThat(cut.get("contact").get("name").stringValue())
                .as("cleaned as a profile name: at most 200 characters")
                .hasSizeLessThanOrEqualTo(200)
                .startsWith("Ab Ab");
    }

    // An existing number (W2, P11)

    @Test
    void P11_staffGetABare409ForANumberWithAnotherMembersOpenLead(CapturedOutput output) {
        Team team = team();
        String colleagues = phone();
        UUID contact = data.contact(team.tenant(), colleagues, "Ravi Kumar");
        data.lead(team.tenant(), team.version(), contact, null, team.colleagueMembership());
        String unassigned = phone();
        UUID nobodys = data.contact(team.tenant(), unassigned, "Meera");
        data.lead(team.tenant(), team.version(), nobodys, null, null);

        for (String phone : List.of(colleagues, unassigned)) {
            MvcTestResult result = enter(
                    team.staffUser(), request(phone).put("name", "Someone").put("email", "someone@example.com"));

            assertThat(result).hasStatus(409);
            assertThat(JSON.readTree(body(result)))
                    .as("the code and nothing else: no name, assignee or ID")
                    .isEqualTo(JSON.readTree("""
                            {"code": "contact_exists",
                             "message": "A contact with this phone number already has an open lead",
                             "details": {"requestId": "%s"}}""".formatted(REQUEST_ID)));
        }
        assertThat(leadsOf(contact)).containsExactly("qualifying|direct|" + team.colleagueMembership() + "||open");
        assertThat(sourcesOf(contact)).isEmpty();
        assertThat(admin.sql("select name || '|' || coalesce(email, '') from northstar.contacts where id = :id")
                        .param("id", contact)
                        .query(String.class)
                        .single())
                .isEqualTo("Ravi Kumar|");
        assertThat(staffSee(team, "select count(*) from northstar.contacts where id = :id", contact))
                .isZero();
        assertThat(requestKeysOf(team.tenant())).isEmpty();
        assertThat(output).contains("lead.create_refused reason=contact_exists lead_visible=false");
    }

    @Test
    void P11_aStaffMembersOwnOpenLeadIsNamedInThe409() {
        Team team = team();
        String phone = phone();
        UUID contact = data.contact(team.tenant(), phone, "Asha");
        UUID own = data.lead(team.tenant(), team.version(), contact, null, team.staffMembership());

        MvcTestResult result = enter(team.staffUser(), request(phone));

        assertThat(result).hasStatus(409);
        JsonNode error = JSON.readTree(body(result));
        assertThat(error.get("code").stringValue()).isEqualTo("contact_exists");
        assertThat(error.get("details").get("leadId").stringValue()).isEqualTo(own.toString());
        assertThat(leadsOf(contact)).hasSize(1);
    }

    @Test
    void anAdminsConflictNamesTheOpenLeadWhoeverItIsAssignedTo() {
        Team team = team();
        String phone = phone();
        UUID contact = data.contact(team.tenant(), phone, null);
        UUID colleagues = data.lead(team.tenant(), team.version(), contact, null, team.colleagueMembership());

        MvcTestResult result = enter(team.adminUser(), request(phone));

        assertThat(result).hasStatus(409);
        assertThat(JSON.readTree(body(result)).get("details").get("leadId").stringValue())
                .isEqualTo(colleagues.toString());
    }

    @Test
    void W2_aReturningCustomerGetsANewLeadAndKeepsTheirDetails() {
        Team team = team();
        for (String closing : List.of("lost", "junk", "completed", "cancelled")) {
            String phone = phone();
            UUID contact = data.contact(team.tenant(), phone, "Ravi Kumar");
            UUID closed = data.lead(team.tenant(), team.version(), contact, null, team.colleagueMembership());
            data.closeLead(closed, closing);
            admin.sql("insert into northstar.contact_sources (tenant_id, contact_id, source) values (:t, :c, 'direct')")
                    .param("t", team.tenant())
                    .param("c", contact)
                    .update();

            MvcTestResult result = enter(
                    team.staffUser(), request(phone).put("name", "Someone Else").put("email", "ravi@example.com"));

            assertThat(result).as(closing).hasStatus(201);
            JsonNode lead = json(result);
            assertThat(contactId(lead)).as("the same contact").isEqualTo(contact);
            assertThat(lead.get("contact").get("name").stringValue())
                    .as("a name already there is never replaced")
                    .isEqualTo("Ravi Kumar");
            assertThat(lead.get("contact").get("email").stringValue())
                    .as("an empty email is filled")
                    .isEqualTo("ravi@example.com");
            assertThat(leadsOf(contact))
                    .containsExactlyInAnyOrder(
                            closing + "|direct|" + team.colleagueMembership() + "||closed",
                            "new|manual|" + team.staffMembership() + "||open");
            assertThat(sourcesOf(contact)).containsExactlyInAnyOrder("direct", "manual");
            assertThat(audit(team.tenant()))
                    .as("no contact was created")
                    .contains("lead.created|leads|" + id(lead) + "|" + team.staffUser())
                    .noneMatch(entry -> entry.startsWith("contact.created|contacts|" + contact));
        }
    }

    @Test
    void W2_aContactWithNoLeadAtAllGetsOneAndABlankNameIsFilled() {
        Team team = team();
        String phone = phone();
        UUID contact = data.contact(team.tenant(), phone, "   ");
        UUID conversation = data.conversation(
                team.tenant(), contact, data.whatsAppNumber(team.tenant()), team.colleagueMembership());

        JsonNode lead = json(enter(team.staffUser(), request(phone).put("name", "Asha Rao")), 201);

        assertThat(contactId(lead)).isEqualTo(contact);
        assertThat(lead.get("contact").get("name").stringValue()).isEqualTo("Asha Rao");
        assertThat(lead.get("conversationId").isNull())
                .as("not linked now; the inbound job links it at the next message (step 2.4b)")
                .isTrue();
        assertThat(admin.sql("select assigned_membership_id from northstar.conversations where id = :id")
                        .param("id", conversation)
                        .query(UUID.class)
                        .single())
                .as("the conversation keeps its assignee until reassignment (step 3.9)")
                .isEqualTo(team.colleagueMembership());
    }

    @Test
    void P11_aReturningCustomersContactIsTheirsButNotItsHistory() {
        Team team = team();
        String phone = phone();
        UUID contact = data.contact(team.tenant(), phone, "Ravi Kumar");
        UUID conversation = data.conversation(
                team.tenant(), contact, data.whatsAppNumber(team.tenant()), team.colleagueMembership());
        UUID closed = data.lead(team.tenant(), team.version(), contact, conversation, team.colleagueMembership());
        data.closeLead(closed, "lost");
        data.message(team.tenant(), conversation);

        assertThat(json(enter(team.staffUser(), request(phone)), 201)
                        .get("contact")
                        .get("id")
                        .stringValue())
                .isEqualTo(contact.toString());

        assertThat(staffSee(team, "select count(*) from northstar.contacts where id = :id", contact))
                .isOne();
        assertThat(staffSee(team, "select count(*) from northstar.leads where id = :id", closed))
                .as("the colleague's closed lead")
                .isZero();
        assertThat(staffSee(team, "select count(*) from northstar.conversations where id = :id", conversation))
                .isZero();
        assertThat(staffSee(team, "select count(*) from northstar.messages where conversation_id = :id", conversation))
                .isZero();
        assertThat(get(team.staffUser(), "/api/v1/conversations/" + conversation))
                .hasStatus(404);
    }

    @Test
    void W32_anOptedOutContactStillGetsALeadAndStaysOptedOut() {
        Team team = team();
        String phone = phone();
        UUID contact = data.contact(team.tenant(), phone, null);
        admin.sql("""
                        update northstar.contacts set consent_state = 'opted_out', opt_out_at = now(),
                          last_opt_out_at = now() where id = :id""").param("id", contact).update();

        JsonNode lead = json(enter(team.adminUser(), request(phone)), 201);

        assertThat(lead.get("contact").get("consentState").stringValue()).isEqualTo("opted_out");
        assertThat(lead.get("contact").get("optedOutAt").isNull()).isFalse();
    }

    @Test
    void P9_anotherTenantsContactWithTheSameNumberIsNeverRevealed() {
        Team team = team();
        Team other = team();
        String phone = phone();
        UUID theirs = data.contact(other.tenant(), phone, "Other Business Customer");
        data.lead(other.tenant(), other.version(), theirs, null, other.staffMembership());

        for (UUID user : List.of(team.staffUser(), team.adminUser())) {
            String mine = phone();
            UUID theirsToo = data.contact(other.tenant(), mine, "Theirs Too");
            JsonNode lead = json(enter(user, request(user.equals(team.staffUser()) ? phone : mine)), 201);

            assertThat(contactId(lead)).isNotIn(theirs, theirsToo);
            assertThat(body(lead)).doesNotContain(theirs.toString(), "Other Business", "Theirs Too");
        }
        assertThat(leadsOf(theirs)).hasSize(1);
        assertThat(sourcesOf(theirs)).isEmpty();
        assertThat(contactsWithPhone(team.tenant(), phone)).isOne();
        assertThat(contactsWithPhone(other.tenant(), phone)).isOne();
    }

    // P21

    @Test
    void P21_theSameRequestTwiceCreatesOneContactAndOneLead() {
        Team team = team();
        String phone = phone();
        String key = key();

        MvcTestResult first = enter(team.staffUser(), request(national(phone)).put("name", "Asha"), key);
        MvcTestResult again = enter(team.staffUser(), request(national(phone)).put("name", "Asha"), key);
        MvcTestResult typedOtherwise = enter(team.staffUser(), request(phone).put("name", " Asha "), key);

        assertThat(first).hasStatus(201);
        assertThat(again).hasStatus(200);
        assertThat(typedOtherwise).as("the same number and name, normalised").hasStatus(200);
        assertThat(json(again)).isEqualTo(json(first));
        assertThat(json(typedOtherwise)).isEqualTo(json(first));
        assertThat(contactsWithPhone(team.tenant(), phone)).isOne();
        assertThat(count("select count(*) from northstar.leads where tenant_id = :id", team.tenant()))
                .isOne();
        assertThat(audit(team.tenant())).hasSize(2);
        assertThat(requestKeysOf(team.tenant())).hasSize(1);
    }

    @Test
    void P21_aDoubleSubmitAtOnceCreatesOneContactAndOneLead() throws Exception {
        Team team = team();
        String phone = phone();
        String key = key();

        List<MvcTestResult> results = together(5, () -> enter(team.staffUser(), request(phone), key));

        List<Integer> statuses =
                results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 200);
        assertThat(results.stream().map(r -> id(json(r))).distinct()).hasSize(1);
        assertThat(contactsWithPhone(team.tenant(), phone)).isOne();
        assertThat(count("select count(*) from northstar.leads where tenant_id = :id", team.tenant()))
                .isOne();
        assertThat(audit(team.tenant())).hasSize(2);
        assertThat(requestKeysOf(team.tenant())).hasSize(1);
    }

    @Test
    void P21_aKeyUsedForAnotherRequestIs422AndKeysBelongToOneMember() {
        Team team = team();
        String phone = phone();
        String key = key();
        UUID lead = id(json(enter(team.adminUser(), request(phone).put("name", "Asha"), key), 201));

        for (ObjectNode other : List.of(
                request(phone).put("name", "Asha Rao"),
                request(phone).put("name", "Asha").put("email", "asha@example.com"),
                request(phone()).put("name", "Asha"),
                request(phone)
                        .put("name", "Asha")
                        .put("assignedMembershipId", team.staffMembership().toString()))) {
            MvcTestResult result = enter(team.adminUser(), other, key);
            assertThat(result).as(other.toString()).hasStatus(422);
            assertThat(code(result)).isEqualTo("idempotency_key_reused");
        }
        UUID theirs = id(json(enter(team.staffUser(), request(phone()), key), 201));
        assertThat(theirs)
                .as("another member's key of the same text is their own")
                .isNotEqualTo(lead);
        assertThat(count("select count(*) from northstar.leads where tenant_id = :id", team.tenant()))
                .isEqualTo(2);
    }

    @Test
    void P21_aRefusedRequestLeavesItsKeyFreeToUseAgain() {
        Team team = team();
        String phone = phone();
        UUID contact = data.contact(team.tenant(), phone, null);
        UUID open = data.lead(team.tenant(), team.version(), contact, null, team.colleagueMembership());
        String key = key();

        assertThat(enter(team.staffUser(), request(phone), key)).hasStatus(409);
        data.closeLead(open, "lost");

        assertThat(enter(team.staffUser(), request(phone), key))
                .as("the same request, once the open lead closed")
                .hasStatus(201);
    }

    @Test
    void differentKeysForOneNewNumberAtOnceOpenOneLead() throws Exception {
        Team team = team();
        String phone = phone();

        List<MvcTestResult> results = together(5, () -> enter(team.staffUser(), request(phone), key()));

        List<Integer> statuses =
                results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 409);
        UUID lead = results.stream()
                .filter(r -> r.getResponse().getStatus() == 201)
                .map(r -> id(json(r)))
                .findFirst()
                .orElseThrow();
        assertThat(results.stream()
                        .filter(r -> r.getResponse().getStatus() == 409)
                        .map(r -> JSON.readTree(body(r))
                                .get("details")
                                .get("leadId")
                                .stringValue()))
                .as("the open lead is their own, so it is named")
                .containsOnly(lead.toString());
        assertThat(contactsWithPhone(team.tenant(), phone)).isOne();
        assertThat(count("select count(*) from northstar.leads where tenant_id = :id", team.tenant()))
                .isOne();
    }

    // Context and logs

    @Test
    void P10_theServicesRefuseWithoutAMemberInScope() {
        Team team = team();
        String phone = phone();

        assertThatThrownBy(() -> transactions.execute(status -> contacts.lockExisting(phone)))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> transactions.execute(status -> leads.publishedWorkflowVersion()))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> transactions.execute(
                        status -> requestKeys.claim(RequestKeys.LEAD_CREATE, key(), "a".repeat(64), UUID.randomUUID())))
                .isInstanceOf(MissingTenantContextException.class);
        for (Runnable asSystem : List.<Runnable>of(
                () -> transactions.execute(status -> contacts.create(phone, null, null)),
                () -> transactions.execute(status -> leads.publishedWorkflowVersion()),
                () -> transactions.execute(status ->
                        requestKeys.claim(RequestKeys.LEAD_CREATE, key(), "a".repeat(64), UUID.randomUUID())))) {
            assertThatThrownBy(() -> SystemScope.call(team.tenant(), () -> {
                        asSystem.run();
                        return null;
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("admins and staff");
        }
        assertThat(contactsWithPhone(team.tenant(), phone)).isZero();
    }

    @Test
    void logsCarryIdsAndCodesButNeverThePhoneNameEmailOrKey(CapturedOutput output) {
        Team team = team();
        String phone = phone();
        String key = "secret-key-" + UUID.randomUUID();
        String name = "Zubeida Quraishi";
        String email = "zubeida.q@example.com";

        UUID lead = id(json(
                enter(
                        team.staffUser(),
                        request(national(phone)).put("name", name).put("email", email),
                        key),
                201));
        assertThat(enter(
                        team.staffUser(),
                        request(national(phone)).put("name", name).put("email", email),
                        key))
                .hasStatus(200);
        assertThat(enter(team.adminUser(), request(phone).put("name", name), key()))
                .hasStatus(409);
        assertThat(enter(team.adminUser(), request("98765 4321O"))).hasStatus(400);

        assertThat(output)
                .contains("lead.created_manually lead_id=" + lead)
                .contains("contact_created=true")
                .contains("lead.create_repeated lead_id=" + lead)
                .contains("lead.create_refused reason=contact_exists lead_visible=true")
                .doesNotContain(phone, national(phone))
                .doesNotContain(name, "Zubeida", email, key)
                .doesNotContain("98765 4321O");
    }

    // Helpers

    private record Team(
            UUID tenant,
            UUID version,
            UUID adminUser,
            UUID adminMembership,
            UUID staffUser,
            UUID staffMembership,
            UUID colleagueMembership) {}

    private Team team() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID colleague = data.membership(owner.tenantId(), data.user(), "staff", "active");
        return new Team(
                owner.tenantId(),
                data.workflowVersion(owner.tenantId()),
                owner.userId(),
                owner.membershipId(),
                staffUser,
                staffMembership,
                colleague);
    }

    private static ObjectNode request(String phone) {
        return JSON.createObjectNode().put("phone", phone);
    }

    private MvcTestResult enter(UUID user, ObjectNode request) {
        return enter(user, request, key());
    }

    private MvcTestResult enter(UUID user, ObjectNode request, String idempotencyKey) {
        var post = mvc.post()
                .uri("/api/v1/leads")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, REQUEST_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.toString());
        if (idempotencyKey != null) post = post.header("Idempotency-Key", idempotencyKey);
        return post.exchange();
    }

    private MvcTestResult get(UUID user, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, REQUEST_ID)
                .exchange();
    }

    /** Runs the requests on their own threads, all released at once, and returns their results. */
    private List<MvcTestResult> together(int count, java.util.concurrent.Callable<MvcTestResult> request)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcTestResult>> submitted = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            submitted.add(threads.submit(() -> {
                ready.countDown();
                go.await();
                return request.call();
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<MvcTestResult> results = new ArrayList<>();
        for (Future<MvcTestResult> result : submitted) results.add(result.get(60, TimeUnit.SECONDS));
        return results;
    }

    private long staffSee(Team team, String countSql, UUID id) {
        return app.as(
                team.tenant(),
                team.staffUser(),
                "staff",
                jdbc -> jdbc.sql(countSql).param("id", id).query(Long.class).single());
    }

    /** stage|origin|assignee|conversation|open or closed, per lead of the contact. */
    private List<String> leadsOf(UUID contact) {
        return admin.sql("""
                        select stage || '|' || origin || '|' || coalesce(assigned_membership_id::text, '') || '|'
                               || coalesce(conversation_id::text, '') || '|'
                               || case when closed_at is null then 'open' else 'closed' end
                        from northstar.leads where contact_id = :id""").param("id", contact).query(String.class).list();
    }

    private List<String> sourcesOf(UUID contact) {
        return admin.sql("select source from northstar.contact_sources where contact_id = :id")
                .param("id", contact)
                .query(String.class)
                .list();
    }

    /** action|target_table|target_id|actor_user_id, per audit entry of the tenant. */
    private List<String> audit(UUID tenant) {
        return admin.sql("""
                        select action || '|' || target_table || '|' || target_id || '|' || actor_user_id
                        from northstar.audit_log where tenant_id = :t""").param("t", tenant).query(String.class).list();
    }

    /** operation|membership|target, per request key of the tenant. */
    private List<String> requestKeysOf(UUID tenant) {
        return admin.sql("""
                        select operation || '|' || membership_id || '|' || target_id
                        from northstar.request_keys where tenant_id = :t""").param("t", tenant).query(String.class).list();
    }

    private long contactsWithPhone(UUID tenant, String phone) {
        return admin.sql("select count(*) from northstar.contacts where tenant_id = :t and normalized_phone = :p")
                .param("t", tenant)
                .param("p", phone)
                .query(Long.class)
                .single();
    }

    private long count(String sql, UUID id) {
        return admin.sql(sql).param("id", id).query(Long.class).single();
    }

    /** A random Indian mobile in E.164. */
    private static String phone() {
        return "+9196" + (10_000_000 + (long) (Math.random() * 89_999_999L));
    }

    /** The 10 digits after +91. */
    private static String national(String phone) {
        return phone.substring(3);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode json(MvcTestResult result, int status) {
        assertThat(result).hasStatus(status);
        return json(result);
    }

    private static JsonNode json(MvcTestResult result) {
        return JSON.readTree(body(result));
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String body(JsonNode node) {
        return node.toString();
    }

    private static String code(MvcTestResult result) {
        return code(body(result));
    }

    private static String code(String body) {
        return JSON.readTree(body).get("code").stringValue();
    }

    private static UUID id(JsonNode lead) {
        return UUID.fromString(lead.get("id").stringValue());
    }

    private static UUID contactId(JsonNode lead) {
        return UUID.fromString(lead.get("contact").get("id").stringValue());
    }
}
