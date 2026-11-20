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
 * northstar.events, the engine's input log (step 2.4a, data-model.md section 6), through the real app_user login with
 * RLS: one event per dedupe key in its tenant (P1), the checks that keep it to IDs, its lifecycle grants, and who sees
 * which event (P11, P10). Another tenant's rows are covered for every table by CrossTenantLeakTests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventsSchemaTests {

    private static final UUID NIL_USER = TenantContext.SYSTEM_USER_ID;

    /** The recording statement step 2.4b uses: a second copy of an event stores nothing. */
    private static final String RECORD = """
            insert into northstar.events (tenant_id, lead_id, type, dedupe_key, payload)
            values (:t, :lead, 'message.received', :key, cast(:payload as jsonb))
            on conflict (tenant_id, dedupe_key) do nothing
            returning id""";

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;

    EventsSchemaTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void P1_anEventIsRecordedOncePerDedupeKeyInItsTenant() {
        UUID tenant = data.tenant();
        UUID otherTenant = data.tenant();
        String key = "message.received:wamid.TEST" + UUID.randomUUID();

        List<UUID> first = record(tenant, null, key);
        List<UUID> second = record(tenant, null, key);
        List<UUID> elsewhere = record(otherTenant, null, key);

        assertThat(first).hasSize(1);
        assertThat(second).as("a second copy stores nothing").isEmpty();
        assertThat(elsewhere).as("dedupe keys are unique per tenant").hasSize(1);
        assertThat(admin.sql("select count(*) from northstar.events where dedupe_key = :k")
                        .param("k", key)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void columnsAreChecked() {
        UUID tenant = data.tenant();
        for (String[] bad : new String[][] {
            {"'message.sent'", "'k1'", "'{}'"}, // not an event type of workflow-spec.md section 6
            {"'message.received'", "''", "'{}'"}, // empty dedupe key
            {"'message.received'", "repeat('k', 301)", "'{}'"}, // dedupe key too long
            {"'message.received'", "'k2'", "'[]'"}, // payload not an object
            {"'message.received'", "'k3'", "'\"text\"'"},
            {"'message.received'", "'k4'", "jsonb_build_object('x', repeat('a', 4100))"} // too big for IDs only
        }) {
            assertThatThrownBy(() -> admin.sql("""
                                    insert into northstar.events (tenant_id, type, dedupe_key, payload)
                                    values (:t, %s, %s, %s)""".formatted(bad[0], bad[1], bad[2]))
                            .param("t", tenant)
                            .update())
                    .as(String.join(", ", bad))
                    .hasMessageContaining("check constraint");
        }
        assertThat(admin.sql("""
                        insert into northstar.events (tenant_id, type, dedupe_key)
                        values (:t, 'payment.captured', 'pay_TEST1') returning payload::text""").param("t", tenant).query(String.class).single())
                .as("payload defaults to an empty object")
                .isEqualTo("{}");
    }

    @Test
    void anEventsLeadMustBeItsTenantsOwn() {
        UUID tenant = data.tenant();
        UUID foreignTenant = data.tenant();
        UUID foreignLead = data.lead(foreignTenant, data.workflowVersion(foreignTenant), null);

        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.events (tenant_id, lead_id, type, dedupe_key)
                                values (:t, :lead, 'message.received', :key)""")
                        .param("t", tenant)
                        .param("lead", foreignLead)
                        .param("key", "k-" + UUID.randomUUID())
                        .update())
                .hasMessageContaining("foreign key");
    }

    @Test
    void onlyProcessedAtChangesAndNothingIsDeleted() {
        assertThat(appUserColumns("UPDATE")).containsExactly("processed_at");
        assertThat(appUserColumns("INSERT"))
                .containsExactlyInAnyOrder("id", "tenant_id", "lead_id", "type", "dedupe_key", "payload");
        assertThat(admin.sql("""
                        select p from unnest(array['DELETE', 'TRUNCATE']) p
                        where has_table_privilege('app_user', 'northstar.events', p)""").query(String.class).list()).isEmpty();
        assertThat(admin.sql("select has_table_privilege('app_admin', 'northstar.events', 'SELECT')")
                        .query(Boolean.class)
                        .single())
                .as("events hold IDs only, so the admin panel (Phase 8) may read them")
                .isTrue();

        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID event = record(tenant, null, "k-" + UUID.randomUUID()).getFirst();
        app.run(
                tenant,
                NIL_USER,
                "system",
                jdbc -> assertThat(jdbc.sql("update northstar.events set processed_at = now() where id = :id")
                                .param("id", event)
                                .update())
                        .isEqualTo(1));
        for (String statement : List.of(
                "update northstar.events set type = 'staff.action' where id = '" + event + "'",
                "update northstar.events set dedupe_key = 'rewritten' where id = '" + event + "'",
                "update northstar.events set payload = '{}' where id = '" + event + "'",
                "update northstar.events set received_at = now() where id = '" + event + "'",
                "delete from northstar.events where id = '" + event + "'")) {
            assertThatThrownBy(() -> app.run(
                            tenant,
                            owner.userId(),
                            "admin",
                            jdbc -> jdbc.sql(statement).update()))
                    .as(statement)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void P11_staffSeeOnlyTheEventsOfTheirAssignedLeads() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        UUID staff = data.membership(tenant, staffUser, "staff", "active");
        UUID colleague = data.membership(tenant, data.user(), "staff", "active");
        UUID version = data.workflowVersion(tenant);
        UUID myLead = data.lead(tenant, version, staff);
        UUID theirLead = data.lead(tenant, version, colleague);
        UUID unassignedLead = data.lead(tenant, version, null);

        UUID mine = record(tenant, myLead, "k-" + UUID.randomUUID()).getFirst();
        UUID theirs = record(tenant, theirLead, "k-" + UUID.randomUUID()).getFirst();
        UUID unassigned =
                record(tenant, unassignedLead, "k-" + UUID.randomUUID()).getFirst();
        UUID noLead = record(tenant, null, "k-" + UUID.randomUUID()).getFirst();

        assertThat(idsSeenBy(tenant, staffUser, "staff")).containsExactly(mine);
        assertThat(idsSeenBy(tenant, owner.userId(), "admin"))
                .containsExactlyInAnyOrder(mine, theirs, unassigned, noLead);
        assertThat(idsSeenBy(tenant, NIL_USER, "system")).containsExactlyInAnyOrder(mine, theirs, unassigned, noLead);

        // Staff record events only for their own leads; a lead-less event is for admins and the system.
        int ownLeadEvents = app.as(tenant, staffUser, "staff", jdbc -> insert(jdbc, tenant, myLead));
        assertThat(ownLeadEvents).isEqualTo(1);
        for (UUID lead : new UUID[] {theirLead, unassignedLead, null}) {
            assertThatThrownBy(() -> app.as(tenant, staffUser, "staff", jdbc -> insert(jdbc, tenant, lead)))
                    .as("staff recording an event on lead " + lead)
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
        // A deactivated staff member sees nothing, even of their own leads.
        admin.sql("update northstar.memberships set status = 'deactivated' where id = :m")
                .param("m", staff)
                .update();
        assertThat(idsSeenBy(tenant, staffUser, "staff")).isEmpty();
    }

    @Test
    void P10_webhookAndUnknownRolesSeeNoEvents() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID lead = data.lead(tenant, data.workflowVersion(tenant), null);
        record(tenant, lead, "k-" + UUID.randomUUID());
        record(tenant, null, "k-" + UUID.randomUUID());

        for (String role : List.of("webhook", "", "owner", "Admin", "System")) {
            UUID user = role.equals("webhook") ? NIL_USER : owner.userId();
            assertThat(idsSeenBy(tenant, user, role)).as("role '" + role + "'").isEmpty();
            assertThatThrownBy(() -> app.as(tenant, user, role, jdbc -> insert(jdbc, tenant, null)))
                    .as("an event recorded as role '" + role + "'")
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
        long withoutContext = app.withoutContext(jdbc -> jdbc.sql("select count(*) from northstar.events")
                .query(Long.class)
                .single());
        assertThat(withoutContext).as("no tenant context matches nothing").isZero();
    }

    // Helpers

    private List<UUID> record(UUID tenant, UUID lead, String key) {
        return app.as(
                tenant,
                NIL_USER,
                "system",
                jdbc -> jdbc.sql(RECORD)
                        .param("t", tenant)
                        .param("lead", lead)
                        .param("key", key)
                        .param("payload", "{\"webhook_event_id\": \"" + UUID.randomUUID() + "\"}")
                        .query(UUID.class)
                        .list());
    }

    private static int insert(JdbcClient jdbc, UUID tenant, UUID lead) {
        return jdbc.sql("""
                        insert into northstar.events (tenant_id, lead_id, type, dedupe_key)
                        values (:t, :lead, 'staff.action', :key)""")
                .param("t", tenant)
                .param("lead", lead)
                .param("key", "k-" + UUID.randomUUID())
                .update();
    }

    private List<UUID> idsSeenBy(UUID tenant, UUID user, String role) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("select id from northstar.events where tenant_id = :t")
                        .param("t", tenant)
                        .query(UUID.class)
                        .list());
    }

    private List<String> appUserColumns(String privilege) {
        return admin.sql("""
                        select a.attname from pg_attribute a
                        where a.attrelid = 'northstar.events'::regclass and a.attnum > 0 and not a.attisdropped
                          and has_column_privilege('app_user', a.attrelid, a.attnum, :p)""").param("p", privilege).query(String.class).list();
    }
}
