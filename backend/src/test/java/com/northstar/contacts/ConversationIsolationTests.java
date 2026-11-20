package com.northstar.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Who sees which conversations, messages, contacts and templates (step 2.3, P11, P10), through the real app_user login
 * with RLS: staff only their assigned conversations, those conversations' messages, and the contacts of their own
 * leads and conversations; admins and the system actor the whole tenant; the webhook role and unknown roles nothing.
 * Templates: everyone in the business reads them, only admins and the system actor write them. Another tenant's rows
 * are covered for every table by CrossTenantLeakTests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConversationIsolationTests {

    private static final UUID NIL_USER = TenantContext.SYSTEM_USER_ID;

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;

    ConversationIsolationTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /**
     * A business with an admin and two staff members: one conversation for each staff member and one unassigned, each
     * with a contact of its own and one inbound message.
     */
    private record Shop(
            UUID tenant,
            TestData.Member owner,
            UUID staffUser,
            UUID staffMembership,
            UUID colleagueUser,
            UUID colleagueMembership,
            UUID mine,
            UUID theirs,
            UUID unassigned) {}

    private Shop shop() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(tenant, staffUser, "staff", "active");
        UUID colleagueUser = data.user();
        UUID colleagueMembership = data.membership(tenant, colleagueUser, "staff", "active");
        Shop shop = new Shop(
                tenant,
                owner,
                staffUser,
                staffMembership,
                colleagueUser,
                colleagueMembership,
                data.conversation(tenant, data.contact(tenant), staffMembership),
                data.conversation(tenant, data.contact(tenant), colleagueMembership),
                data.conversation(tenant, data.contact(tenant), null));
        for (UUID conversation : List.of(shop.mine(), shop.theirs(), shop.unassigned())) {
            data.message(tenant, conversation);
        }
        return shop;
    }

    @Test
    void P11_staffSeeOnlyTheirAssignedConversations() {
        Shop shop = shop();
        UUID staff = shop.staffUser();

        assertThat(idsSeenBy(shop.tenant(), staff, "staff", "northstar.conversations"))
                .containsExactly(shop.mine());
        assertThat(asStaff(shop, "update northstar.conversations set needs_human = true where id = :id", shop.mine()))
                .isOne();
        assertThat(asStaff(shop, "update northstar.conversations set ai_paused = true where id = :id", shop.theirs()))
                .as("a colleague's conversation is not there to change")
                .isZero();
        assertThat(asStaff(
                        shop,
                        "update northstar.conversations set assigned_membership_id = '" + shop.staffMembership()
                                + "' where id = :id",
                        shop.unassigned()))
                .as("taking an unassigned conversation")
                .isZero();
        assertThatThrownBy(() -> asStaff(
                        shop,
                        "update northstar.conversations set assigned_membership_id = '" + shop.colleagueMembership()
                                + "' where id = :id",
                        shop.mine()))
                .as("handing their own conversation to someone else")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> insertConversationAs(shop, staff, "staff", null))
                .as("creating an unassigned conversation")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(insertConversationAs(shop, staff, "staff", shop.staffMembership()))
                .isOne();
        assertThatThrownBy(() -> asStaff(shop, "delete from northstar.conversations where id = :id", shop.mine()))
                .rootCause()
                .hasMessageContaining("permission denied");

        assertThat(stateOf(shop.theirs())).isEqualTo("paused=false assigned=" + shop.colleagueMembership());
        assertThat(stateOf(shop.unassigned())).isEqualTo("paused=false assigned=none");
        assertThat(stateOf(shop.mine())).isEqualTo("paused=false assigned=" + shop.staffMembership());
    }

    @Test
    void P11_staffSeeOnlyTheMessagesOfTheirConversations() {
        Shop shop = shop();
        UUID staff = shop.staffUser();
        UUID myMessage = messageOf(shop.mine());
        UUID theirMessage = messageOf(shop.theirs());

        assertThat(idsSeenBy(shop.tenant(), staff, "staff", "northstar.messages"))
                .containsExactly(myMessage);
        assertThat(asStaff(shop, "update northstar.messages set read_at = read_at where id = :id", myMessage))
                .isOne();
        assertThat(asStaff(shop, "update northstar.messages set read_at = read_at where id = :id", theirMessage))
                .isZero();
        assertThat(replyAs(shop, staff, shop.staffMembership(), shop.mine())).isOne();
        assertThatThrownBy(() -> replyAs(shop, staff, shop.staffMembership(), shop.theirs()))
                .as("writing into a colleague's conversation")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> replyAs(shop, staff, shop.staffMembership(), shop.unassigned()))
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> asStaff(shop, "delete from northstar.messages where id = :id", myMessage))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void P11_reassigningAConversationMovesItsMessages() {
        Shop shop = shop();
        UUID message = messageOf(shop.mine());

        assertThat(changeAs(
                        shop.tenant(),
                        shop.owner().userId(),
                        "admin",
                        "update northstar.conversations set assigned_membership_id = '" + shop.colleagueMembership()
                                + "' where id = :id",
                        shop.mine()))
                .isOne();

        assertThat(idsSeenBy(shop.tenant(), shop.staffUser(), "staff", "northstar.messages"))
                .isEmpty();
        assertThat(idsSeenBy(shop.tenant(), shop.colleagueUser(), "staff", "northstar.messages"))
                .containsExactlyInAnyOrder(message, messageOf(shop.theirs()));
        assertThat(idsSeenBy(shop.tenant(), shop.staffUser(), "staff", "northstar.contacts"))
                .as("nor the conversation's contact, with no lead of theirs")
                .isEmpty();
    }

    @Test
    void P11_staffSeeOnlyTheContactsOfTheirLeadsAndConversations() {
        Shop shop = shop();
        UUID tenant = shop.tenant();
        UUID version = data.workflowVersion(tenant);
        UUID viaOpenLead = contactOfLead(data.lead(tenant, version, shop.staffMembership()));
        UUID closedLead = data.lead(tenant, version, shop.staffMembership());
        admin.sql("update northstar.leads set stage = 'lost', closed_at = now() where id = :id")
                .param("id", closedLead)
                .update();
        UUID viaClosedLead = contactOfLead(closedLead);
        UUID viaConversation = contactOfConversation(shop.mine());
        UUID colleagues = contactOfLead(data.lead(tenant, version, shop.colleagueMembership()));
        UUID nobodys = data.contact(tenant);
        for (UUID contact : List.of(
                viaOpenLead,
                viaClosedLead,
                viaConversation,
                colleagues,
                nobodys,
                contactOfConversation(shop.theirs()),
                contactOfConversation(shop.unassigned()))) {
            admin.sql("insert into northstar.contact_sources (tenant_id, contact_id, source) values (:t, :c, 'direct')")
                    .param("t", tenant)
                    .param("c", contact)
                    .update();
        }

        assertThat(idsSeenBy(tenant, shop.staffUser(), "staff", "northstar.contacts"))
                .containsExactlyInAnyOrder(viaOpenLead, viaClosedLead, viaConversation);
        assertThat(contactsOfSourcesSeenBy(tenant, shop.staffUser(), "staff"))
                .containsExactlyInAnyOrder(viaOpenLead, viaClosedLead, viaConversation);
        assertThat(asStaff(shop, "update northstar.contacts set name = 'Asha' where id = :id", viaOpenLead))
                .isOne();
        assertThat(asStaff(shop, "update northstar.contacts set name = 'Asha' where id = :id", colleagues))
                .isZero();
        assertThatThrownBy(() -> app.run(
                        tenant,
                        shop.staffUser(),
                        "staff",
                        jdbc -> jdbc.sql("insert into northstar.contacts (tenant_id, normalized_phone) values (:t, :p)")
                                .param("t", tenant)
                                .param("p", "+9198" + (10_000_000 + (int) (Math.random() * 89_999_999)))
                                .update()))
                .as("staff cannot create contacts until manual lead entry decides how (step 2.10)")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> app.run(
                        tenant,
                        shop.staffUser(),
                        "staff",
                        jdbc -> jdbc.sql("""
                                        insert into northstar.contact_sources (tenant_id, contact_id, source)
                                        values (:t, :c, 'manual')""")
                                .param("t", tenant)
                                .param("c", colleagues)
                                .update()))
                .rootCause()
                .hasMessageContaining("row-level security");

        admin.sql("update northstar.memberships set status = 'deactivated' where id = :id")
                .param("id", shop.staffMembership())
                .update();
        assertThat(idsSeenBy(tenant, shop.staffUser(), "staff", "northstar.contacts"))
                .as("a deactivated member")
                .isEmpty();
        assertThat(idsSeenBy(tenant, shop.staffUser(), "staff", "northstar.conversations"))
                .isEmpty();
    }

    @Test
    void P11_staffReadTemplatesButOnlyAdminsAndTheSystemWriteThem() {
        Shop shop = shop();
        UUID template = data.messageTemplate(shop.tenant());

        assertThat(idsSeenBy(shop.tenant(), shop.staffUser(), "staff", "northstar.message_templates"))
                .containsExactly(template);
        assertThatThrownBy(() -> insertTemplateAs(shop, shop.staffUser(), "staff"))
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(asStaff(shop, "update northstar.message_templates set body = 'Hi' where id = :id", template))
                .isZero();

        assertThat(insertTemplateAs(shop, shop.owner().userId(), "admin")).isOne();
        assertThat(insertTemplateAs(shop, NIL_USER, "system")).isOne();
        assertThat(changeAs(
                        shop.tenant(),
                        NIL_USER,
                        "system",
                        "update northstar.message_templates set status = 'paused', provider_template_id = '1234567890'"
                                + " where id = :id",
                        template))
                .as("the template sync of step 2.8")
                .isOne();
        assertThat(admin.sql("select body from northstar.message_templates where id = :id")
                        .param("id", template)
                        .query(String.class)
                        .single())
                .isEqualTo("Hello {{1}}");
    }

    @Test
    void P10_webhookAndUnknownRolesSeeNothing() {
        Shop shop = shop();
        UUID tenant = shop.tenant();
        UUID contact = contactOfConversation(shop.mine());
        admin.sql("insert into northstar.contact_sources (tenant_id, contact_id, source) values (:t, :c, 'direct')")
                .param("t", tenant)
                .param("c", contact)
                .update();

        // The webhook role (step 2.2) is refused every statement but one by the guard; the database refuses it these
        // tables too. So are an empty role, an unknown one and a wrongly cased one.
        for (String role : List.of("webhook", "", "owner", "Admin", "System")) {
            UUID user = role.equals("webhook") ? NIL_USER : shop.owner().userId();
            for (String table : List.of(
                    "northstar.conversations",
                    "northstar.messages",
                    "northstar.contacts",
                    "northstar.contact_sources")) {
                assertThat(idsSeenBy(tenant, user, role, table))
                        .as(table + " as role '" + role + "'")
                        .isEmpty();
            }
            assertThatThrownBy(() -> app.run(
                            tenant,
                            user,
                            role,
                            jdbc -> jdbc.sql("""
                                            insert into northstar.messages
                                              (tenant_id, conversation_id, direction, sender_type, provider_message_id,
                                               type)
                                            values (:t, :c, 'in', 'contact', :wamid, 'text')""")
                                    .param("t", tenant)
                                    .param("c", shop.mine())
                                    .param("wamid", "wamid.TEST" + UUID.randomUUID())
                                    .update()))
                    .as("a message stored as role '" + role + "'")
                    .rootCause()
                    .hasMessageContaining("row-level security");
            assertThatThrownBy(() -> insertTemplateAs(shop, user, role))
                    .as("a template written as role '" + role + "'")
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
        assertThatThrownBy(() -> app.run(
                        tenant,
                        NIL_USER,
                        "webhook",
                        jdbc -> jdbc.sql("insert into northstar.contacts (tenant_id, normalized_phone) values (:t, :p)")
                                .param("t", tenant)
                                .param("p", "+9197" + (10_000_000 + (int) (Math.random() * 89_999_999)))
                                .update()))
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> insertConversationAs(shop, NIL_USER, "webhook", null))
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void adminsAndTheSystemSeeTheWholeTenant() {
        Shop shop = shop();
        Shop other = shop();
        UUID[] everyConversation = {shop.mine(), shop.theirs(), shop.unassigned()};

        for (UUID user : List.of(shop.owner().userId(), NIL_USER)) {
            String role = user.equals(NIL_USER) ? "system" : "admin";
            assertThat(idsSeenBy(shop.tenant(), user, role, "northstar.conversations"))
                    .as(role)
                    .containsExactlyInAnyOrder(everyConversation);
            assertThat(idsSeenBy(shop.tenant(), user, role, "northstar.messages"))
                    .as(role)
                    .containsExactlyInAnyOrder(
                            messageOf(shop.mine()), messageOf(shop.theirs()), messageOf(shop.unassigned()));
            assertThat(idsSeenBy(shop.tenant(), user, role, "northstar.contacts"))
                    .as(role)
                    .containsExactlyInAnyOrder(
                            contactOfConversation(shop.mine()),
                            contactOfConversation(shop.theirs()),
                            contactOfConversation(shop.unassigned()));
        }
        assertThat(changeAs(
                        shop.tenant(),
                        shop.owner().userId(),
                        "admin",
                        "update northstar.conversations set needs_human = true where id = :id",
                        other.mine()))
                .as("another tenant's conversation")
                .isZero();
        assertThat(replyAs(shop, shop.owner().userId(), shop.owner().membershipId(), shop.theirs()))
                .as("an admin can take over any conversation")
                .isOne();
    }

    // Helpers.

    private List<UUID> idsSeenBy(UUID tenant, UUID user, String role, String table) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("select id from " + table).query(UUID.class).list());
    }

    private List<UUID> contactsOfSourcesSeenBy(UUID tenant, UUID user, String role) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("select contact_id from northstar.contact_sources")
                        .query(UUID.class)
                        .list());
    }

    /** Runs one statement with a :id parameter as the shop's staff member. */
    private int asStaff(Shop shop, String sql, UUID id) {
        return changeAs(shop.tenant(), shop.staffUser(), "staff", sql, id);
    }

    /** Runs one statement with a :id parameter in the tenant, as this user and role, returning the rows changed. */
    private int changeAs(UUID tenant, UUID user, String role, String sql, UUID id) {
        Integer changed =
                app.as(tenant, user, role, jdbc -> jdbc.sql(sql).param("id", id).update());
        return changed;
    }

    private int insertConversationAs(Shop shop, UUID user, String role, UUID assigned) {
        UUID contact = data.contact(shop.tenant());
        String number = data.whatsAppNumber(shop.tenant());
        Integer inserted = app.as(
                shop.tenant(),
                user,
                role,
                jdbc -> jdbc.sql("""
                                insert into northstar.conversations
                                  (tenant_id, contact_id, channel, whatsapp_phone_number_id, assigned_membership_id)
                                values (:t, :contact, 'whatsapp', :number, :assigned)""")
                        .param("t", shop.tenant())
                        .param("contact", contact)
                        .param("number", number)
                        .param("assigned", assigned)
                        .update());
        return inserted;
    }

    /** A staff member's (or admin's) reply, queued for sending. */
    private int replyAs(Shop shop, UUID user, UUID membership, UUID conversation) {
        String role = membership.equals(shop.owner().membershipId()) ? "admin" : "staff";
        Integer inserted = app.as(
                shop.tenant(),
                user,
                role,
                jdbc -> jdbc.sql("""
                                insert into northstar.messages
                                  (tenant_id, conversation_id, direction, sender_type, sender_membership_id, type, body,
                                   status)
                                values (:t, :c, 'out', 'staff', :me, 'text', 'On it', 'queued')""")
                        .param("t", shop.tenant())
                        .param("c", conversation)
                        .param("me", membership)
                        .update());
        return inserted;
    }

    private int insertTemplateAs(Shop shop, UUID user, String role) {
        Integer inserted = app.as(
                shop.tenant(),
                user,
                role,
                jdbc -> jdbc.sql("""
                                insert into northstar.message_templates (tenant_id, name, language, status)
                                values (:t, :name, 'en', 'draft')""")
                        .param("t", shop.tenant())
                        .param(
                                "name",
                                "isolation_" + UUID.randomUUID().toString().replace("-", ""))
                        .update());
        return inserted;
    }

    private String stateOf(UUID conversation) {
        return admin.sql("""
                        select 'paused=' || ai_paused || ' assigned=' || coalesce(assigned_membership_id::text, 'none')
                        from northstar.conversations where id = :id""").param("id", conversation).query(String.class).single();
    }

    private UUID messageOf(UUID conversation) {
        return admin.sql("select id from northstar.messages where conversation_id = :c and direction = 'in'")
                .param("c", conversation)
                .query(UUID.class)
                .single();
    }

    private UUID contactOfConversation(UUID conversation) {
        return admin.sql("select contact_id from northstar.conversations where id = :id")
                .param("id", conversation)
                .query(UUID.class)
                .single();
    }

    private UUID contactOfLead(UUID lead) {
        return admin.sql("select contact_id from northstar.leads where id = :id")
                .param("id", lead)
                .query(UUID.class)
                .single();
    }
}
