package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * northstar.timeline_events, a lead's history (step 3.3, data-model.md section 10), through the real app_user login with
 * RLS: the checks that keep it to codes and IDs, its contact always its lead's, insert-only for every role, who sees
 * which row (P11, P10), and that a member records only themselves as the actor. Another tenant's rows are covered for
 * every table by CrossTenantLeakTests; P9 here is the composite keys.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TimelineSchemaTests {

    private static final UUID NIL_USER = TenantContext.SYSTEM_USER_ID;

    private static final String INSERT = """
            insert into northstar.timeline_events
              (tenant_id, lead_id, contact_id, type, actor_type, actor_membership_id, summary, data)
            values (:t, :lead, :contact, 'stage_changed', :actor, :membership, 'exit', '{"to": "qualifying"}')""";

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;

    TimelineSchemaTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** A business with an admin, two staff members, and a lead for each staff member and one unassigned. */
    private record Shop(
            UUID tenant,
            TestData.Member owner,
            UUID staffUser,
            UUID staff,
            UUID colleagueUser,
            UUID colleague,
            UUID myLead,
            UUID theirLead,
            UUID unassignedLead) {}

    private Shop shop() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        UUID staff = data.membership(tenant, staffUser, "staff", "active");
        UUID colleagueUser = data.user();
        UUID colleague = data.membership(tenant, colleagueUser, "staff", "active");
        UUID version = data.workflowVersion(tenant);
        return new Shop(
                tenant,
                owner,
                staffUser,
                staff,
                colleagueUser,
                colleague,
                data.lead(tenant, version, staff),
                data.lead(tenant, version, colleague),
                data.lead(tenant, version, null));
    }

    @Test
    void columnsAreChecked() {
        Shop shop = shop();
        UUID contact = contactOf(shop.myLead());
        String columns =
                "(tenant_id, lead_id, contact_id, type, actor_type, actor_membership_id, ref_table, ref_id, summary,"
                        + " data)";
        for (String values : List.of(
                // Not a type built yet (each joins with its step).
                "(:t, :lead, :c, 'message', 'system', null, null, null, 'exit', '{}')",
                "(:t, :lead, :c, 'stage_changed', 'owner', null, null, null, 'exit', '{}')",
                // A staff actor names its membership, and no other actor does.
                "(:t, :lead, :c, 'stage_changed', 'staff', null, null, null, 'exit', '{}')",
                "(:t, :lead, :c, 'stage_changed', 'system', :m, null, null, 'exit', '{}')",
                // A ref has its table and its ID, and only a known table.
                "(:t, :lead, :c, 'stage_changed', 'system', null, 'events', null, 'exit', '{}')",
                "(:t, :lead, :c, 'stage_changed', 'system', null, null, gen_random_uuid(), 'exit', '{}')",
                "(:t, :lead, :c, 'stage_changed', 'system', null, 'notes', gen_random_uuid(), 'exit', '{}')",
                // The summary is a fixed code, never text.
                "(:t, :lead, :c, 'stage_changed', 'system', null, null, null, 'Moved to Lost', '{}')",
                "(:t, :lead, :c, 'stage_changed', 'system', null, null, null, '', '{}')",
                "(:t, :lead, :c, 'stage_changed', 'system', null, null, null, repeat('a', 65), '{}')",
                // data is a small JSON object.
                "(:t, :lead, :c, 'stage_changed', 'system', null, null, null, 'exit', '[]')",
                "(:t, :lead, :c, 'stage_changed', 'system', null, null, null, 'exit', '\"text\"')",
                "(:t, :lead, :c, 'stage_changed', 'system', null, null, null, 'exit',"
                        + " jsonb_build_object('x', repeat('a', 4100)))")) {
            assertThatThrownBy(() -> admin.sql("insert into northstar.timeline_events " + columns + " values " + values)
                            .param("t", shop.tenant())
                            .param("lead", shop.myLead())
                            .param("c", contact)
                            .param("m", shop.staff())
                            .update())
                    .as(values)
                    .hasMessageContaining("check constraint");
        }
        UUID row = admin.sql("insert into northstar.timeline_events " + columns + """
                         values (:t, :lead, :c, 'stage_changed', 'contact', null, 'messages', gen_random_uuid(),
                                 'created', '{"from": null, "to": "new"}')
                        returning id""")
                .param("t", shop.tenant())
                .param("lead", shop.myLead())
                .param("c", contact)
                .query(UUID.class)
                .single();
        assertThat(admin.sql("""
                        select (occurred_at is not null and created_at is not null)::text
                        from northstar.timeline_events where id = :id""").param("id", row).query(String.class).single())
                .as("both times are stamped by the database")
                .isEqualTo("true");
    }

    @Test
    void P9_aRowsLeadContactAndActorAreItsLeadsAndItsTenantsOwn() {
        Shop shop = shop();
        Shop other = shop();
        UUID myContact = contactOf(shop.myLead());

        // Another tenant's lead, the contact of another lead, another tenant's member: all refused.
        assertThatThrownBy(
                        () -> insertAsOwner(shop.tenant(), other.myLead(), contactOf(other.myLead()), "system", null))
                .hasMessageContaining("foreign key");
        assertThatThrownBy(
                        () -> insertAsOwner(shop.tenant(), shop.myLead(), contactOf(shop.theirLead()), "system", null))
                .as("the row's contact is always its lead's own")
                .hasMessageContaining("foreign key");
        assertThatThrownBy(() -> insertAsOwner(shop.tenant(), shop.myLead(), myContact, "staff", other.staff()))
                .hasMessageContaining("foreign key");
        assertThat(insertAsOwner(shop.tenant(), shop.myLead(), myContact, "staff", shop.staff()))
                .isEqualTo(1);
    }

    @Test
    void insertOnlyForEveryRole() {
        Shop shop = shop();
        UUID row = insertAs(shop.tenant(), NIL_USER, "system", shop.myLead(), "system", null);

        assertThat(admin.sql("""
                        select p from unnest(array['UPDATE', 'DELETE', 'TRUNCATE']) p
                        where has_table_privilege('app_user', 'northstar.timeline_events', p)""").query(String.class).list()).isEmpty();
        assertThat(admin.sql("""
                        select a.attname from pg_attribute a
                        where a.attrelid = 'northstar.timeline_events'::regclass and a.attnum > 0
                          and not a.attisdropped and has_column_privilege('app_user', a.attrelid, a.attnum, 'UPDATE')""").query(String.class).list())
                .as("no column is updatable either")
                .isEmpty();
        assertThat(admin.sql("""
                        select p from unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE']) p
                        where has_table_privilege('app_admin', 'northstar.timeline_events', p)""").query(String.class).list())
                .as("rows hold codes and IDs only, so the admin panel (Phase 8) may read them, and nothing more")
                .containsExactly("SELECT");

        for (String[] actor : new String[][] {
            {NIL_USER.toString(), "system"},
            {shop.owner().userId().toString(), "admin"},
            {shop.staffUser().toString(), "staff"}
        }) {
            for (String statement : List.of(
                    "update northstar.timeline_events set summary = 'forced' where id = '" + row + "'",
                    "update northstar.timeline_events set data = '{}' where id = '" + row + "'",
                    "delete from northstar.timeline_events where id = '" + row + "'",
                    "truncate northstar.timeline_events")) {
                assertThatThrownBy(() -> app.run(
                                shop.tenant(),
                                UUID.fromString(actor[0]),
                                actor[1],
                                jdbc -> jdbc.sql(statement).update()))
                        .as(actor[1] + ": " + statement)
                        .rootCause()
                        .hasMessageContaining("permission denied");
            }
        }
        assertThat(admin.sql("select count(*) from northstar.timeline_events where id = :id")
                        .param("id", row)
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void P11_staffSeeAndWriteOnlyTheRowsOfTheirAssignedLeads() {
        Shop shop = shop();
        UUID mine = insertAs(shop.tenant(), NIL_USER, "system", shop.myLead(), "system", null);
        UUID theirs = insertAs(shop.tenant(), NIL_USER, "system", shop.theirLead(), "system", null);
        UUID unassigned = insertAs(shop.tenant(), NIL_USER, "system", shop.unassignedLead(), "system", null);

        assertThat(idsSeenBy(shop.tenant(), shop.staffUser(), "staff")).containsExactly(mine);
        assertThat(idsSeenBy(shop.tenant(), shop.colleagueUser(), "staff")).containsExactly(theirs);
        assertThat(idsSeenBy(shop.tenant(), shop.owner().userId(), "admin"))
                .containsExactlyInAnyOrder(mine, theirs, unassigned);
        assertThat(idsSeenBy(shop.tenant(), NIL_USER, "system")).containsExactlyInAnyOrder(mine, theirs, unassigned);

        // Staff write rows only on their own leads, as themselves.
        insertAs(shop.tenant(), shop.staffUser(), "staff", shop.myLead(), "staff", shop.staff());
        for (UUID lead : new UUID[] {shop.theirLead(), shop.unassignedLead()}) {
            assertThatThrownBy(() -> insertAs(shop.tenant(), shop.staffUser(), "staff", lead, "staff", shop.staff()))
                    .as("staff writing a row on lead " + lead)
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
        // A deactivated staff member sees nothing, even of their own leads.
        admin.sql("update northstar.memberships set status = 'deactivated' where id = :m")
                .param("m", shop.staff())
                .update();
        assertThat(idsSeenBy(shop.tenant(), shop.staffUser(), "staff")).isEmpty();
    }

    @Test
    void P11_aMemberRecordsOnlyThemselvesAsTheActorAndTheSystemAnyActor() {
        Shop shop = shop();

        // Admins and staff: a staff actor with their own membership, nothing else.
        insertAs(
                shop.tenant(),
                shop.owner().userId(),
                "admin",
                shop.theirLead(),
                "staff",
                shop.owner().membershipId());
        insertAs(shop.tenant(), shop.staffUser(), "staff", shop.myLead(), "staff", shop.staff());
        for (Object[] refused : new Object[][] {
            {shop.owner().userId(), "admin", "staff", shop.colleague()},
            {shop.owner().userId(), "admin", "system", null},
            {shop.owner().userId(), "admin", "ai", null},
            {shop.staffUser(), "staff", "staff", shop.colleague()},
            {shop.staffUser(), "staff", "contact", null},
            {shop.staffUser(), "staff", "system", null}
        }) {
            UUID lead = refused[1].equals("admin") ? shop.theirLead() : shop.myLead();
            assertThatThrownBy(() -> insertAs(
                            shop.tenant(), (UUID) refused[0], (String) refused[1], lead, (String) refused[2], (UUID)
                                    refused[3]))
                    .as(refused[1] + " recording " + refused[2] + " " + refused[3])
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
        // The system records any actor: a staff member's action can reach the worker from WhatsApp.
        for (Object[] actor :
                new Object[][] {{"system", null}, {"ai", null}, {"contact", null}, {"staff", shop.colleague()}}) {
            insertAs(shop.tenant(), NIL_USER, "system", shop.unassignedLead(), (String) actor[0], (UUID) actor[1]);
        }
        // A deactivated admin records nothing in their own name any more (another admin keeps the business, P14).
        data.membership(shop.tenant(), data.user(), "admin", "active");
        admin.sql("update northstar.memberships set status = 'deactivated' where id = :m")
                .param("m", shop.owner().membershipId())
                .update();
        assertThatThrownBy(() -> insertAs(
                        shop.tenant(),
                        shop.owner().userId(),
                        "admin",
                        shop.theirLead(),
                        "staff",
                        shop.owner().membershipId()))
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void P10_webhookUnknownAndMissingRolesSeeAndWriteNothing() {
        Shop shop = shop();
        insertAs(shop.tenant(), NIL_USER, "system", shop.myLead(), "system", null);

        for (String role : List.of("webhook", "", "owner", "Admin", "System")) {
            UUID user = role.equals("webhook") ? NIL_USER : shop.owner().userId();
            assertThat(idsSeenBy(shop.tenant(), user, role))
                    .as("role '" + role + "'")
                    .isEmpty();
            assertThatThrownBy(() -> insertAs(shop.tenant(), user, role, shop.myLead(), "system", null))
                    .as("a row written as role '" + role + "'")
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
        long withoutContext = app.withoutContext(jdbc -> jdbc.sql("select count(*) from northstar.timeline_events")
                .query(Long.class)
                .single());
        assertThat(withoutContext).as("no tenant context matches nothing").isZero();
    }

    // Helpers

    private UUID contactOf(UUID lead) {
        return admin.sql("select contact_id from northstar.leads where id = :id")
                .param("id", lead)
                .query(UUID.class)
                .single();
    }

    private int insertAsOwner(UUID tenant, UUID lead, UUID contact, String actor, UUID membership) {
        return admin.sql(INSERT)
                .param("t", tenant)
                .param("lead", lead)
                .param("contact", contact)
                .param("actor", actor)
                .param("membership", membership)
                .update();
    }

    /** Writes a row on the lead as this role, returning its ID. */
    private UUID insertAs(UUID tenant, UUID user, String role, UUID lead, String actor, UUID membership) {
        UUID contact = contactOf(lead);
        UUID id = UUID.randomUUID();
        app.run(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("""
                        insert into northstar.timeline_events
                          (id, tenant_id, lead_id, contact_id, type, actor_type, actor_membership_id, summary, data)
                        values (:id, :t, :lead, :contact, 'stage_changed', :actor, :membership, 'exit', '{}')""")
                        .param("id", id)
                        .param("t", tenant)
                        .param("lead", lead)
                        .param("contact", contact)
                        .param("actor", actor)
                        .param("membership", membership)
                        .update());
        return id;
    }

    private List<UUID> idsSeenBy(UUID tenant, UUID user, String role) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("select id from northstar.timeline_events where tenant_id = :t")
                        .param("t", tenant)
                        .query(UUID.class)
                        .list());
    }
}
