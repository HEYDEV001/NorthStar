package com.northstar;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One valid row per tenant table, for the cross-tenant suite (step 1.14). Generic seeding cannot satisfy check
 * constraints and composite foreign keys reliably, so each tenant table has a one-line fixture here. The suite reads
 * every table with tenant_id from the catalogue and fails, naming the table, when one has no fixture or a fixture names
 * a table that no longer exists: a new tenant table is never silently left out.
 *
 * <p>Rows a staff member could be restricted from (leads, conversations and their messages, and contacts and their
 * sources, which staff see through an assigned conversation) are assigned to the tenant's staff member, so every role
 * has a row of its own tenant to see.
 */
final class TenantFixtures {

    /** A tenant with an admin and a staff member, the people every fixture can refer to. */
    record Side(UUID tenantId, UUID adminUser, UUID adminMembership, UUID staffUser, UUID staffMembership) {}

    /** Table name → seed one row for this side, returning its id. */
    static final Map<String, BiFunction<Side, Seeding, UUID>> FIXTURES = Map.ofEntries(
            Map.entry("northstar.memberships", (side, seed) -> side.staffMembership()),
            Map.entry(
                    "northstar.invites",
                    (side, seed) -> seed.returningId(
                            """
                    insert into northstar.invites (tenant_id, email, role, token_hash, invited_by)
                    values (:t, :email, 'staff', :hash, :by) returning id""",
                            Map.of(
                                    "t", side.tenantId(),
                                    "email", "fixture-" + UUID.randomUUID() + "@example.com",
                                    "hash", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                                    "by", side.adminMembership()))),
            Map.entry(
                    "northstar.audit_log",
                    (side, seed) -> seed.returningId("""
                    insert into northstar.audit_log (tenant_id, actor_user_id, action)
                    values (:t, :actor, 'fixture.seeded') returning id""", Map.of("t", side.tenantId(), "actor", side.adminUser()))),
            Map.entry(
                    "northstar.staff_skills",
                    (side, seed) -> seed.returningId("""
                    insert into northstar.staff_skills (tenant_id, membership_id, skill)
                    values (:t, :m, 'drone') returning id""", Map.of("t", side.tenantId(), "m", side.staffMembership()))),
            Map.entry(
                    "northstar.staff_leave",
                    (side, seed) -> seed.returningId(
                            """
                    insert into northstar.staff_leave (tenant_id, membership_id, starts_on, ends_on, created_by)
                    values (:t, :m, current_date, current_date + 1, :by) returning id""",
                            Map.of("t", side.tenantId(), "m", side.staffMembership(), "by", side.adminMembership()))),
            Map.entry(
                    "northstar.actions",
                    (side, seed) ->
                            seed.returningId("""
                    insert into northstar.actions (tenant_id, type, status, idempotency_key, mode, next_attempt_at)
                    values (:t, 'test.fake', 'pending', :key, 'automatic', now() + interval '1 day') returning id""", Map.of("t", side.tenantId(), "key", "fixture:" + UUID.randomUUID()))),
            Map.entry("northstar.contacts", (side, seed) -> staffContact(side, seed)),
            Map.entry("northstar.workflows", (side, seed) -> seed.returningId("""
                    insert into northstar.workflows (tenant_id, name) values (:t, 'Fixture workflow') returning id""", Map.of("t", side.tenantId()))),
            Map.entry("northstar.workflow_versions", (side, seed) -> seed.data().workflowVersion(side.tenantId())),
            Map.entry(
                    "northstar.leads",
                    (side, seed) -> seed.data()
                            .lead(
                                    side.tenantId(),
                                    seed.data().workflowVersion(side.tenantId()),
                                    side.staffMembership())),
            // Step 2.1a. The credential is checked through secrets_user, the only login that may use its schema.
            Map.entry(
                    "secrets.integration_credentials",
                    (side, seed) -> seed.data().credential(side.tenantId())),
            Map.entry(
                    "northstar.integrations",
                    (side, seed) -> seed.returningId(
                            """
                    insert into northstar.integrations (tenant_id, provider, status, credential_id)
                    values (:t, 'whatsapp', 'connected', :c) returning id""", Map.of("t", side.tenantId(), "c", seed.data().credential(side.tenantId())))),
            Map.entry("northstar.whatsapp_accounts", (side, seed) -> seed.data().whatsAppAccount(side.tenantId())),
            Map.entry(
                    "northstar.webhook_events",
                    (side, seed) -> seed.returningId(
                            """
                    insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)
                    values (:t, 'whatsapp', :event, '{}') returning id""", Map.of("t", side.tenantId(), "event", "wamid.fixture-" + UUID.randomUUID()))),
            // Step 2.3.
            Map.entry(
                    "northstar.conversations",
                    (side, seed) -> seed.data()
                            .conversation(
                                    side.tenantId(), seed.data().contact(side.tenantId()), side.staffMembership())),
            Map.entry(
                    "northstar.messages",
                    (side, seed) -> seed.data()
                            .message(
                                    side.tenantId(),
                                    seed.data()
                                            .conversation(
                                                    side.tenantId(),
                                                    seed.data().contact(side.tenantId()),
                                                    side.staffMembership()))),
            Map.entry("northstar.message_templates", (side, seed) -> seed.data().messageTemplate(side.tenantId())),
            Map.entry(
                    "northstar.contact_sources",
                    (side, seed) ->
                            seed.returningId("""
                    insert into northstar.contact_sources (tenant_id, contact_id, source)
                    values (:t, :contact, 'direct') returning id""", Map.of("t", side.tenantId(), "contact", staffContact(side, seed)))));

    /** A contact the tenant's staff member sees: its conversation is assigned to them (step 2.3). */
    private static UUID staffContact(Side side, Seeding seed) {
        UUID contact = seed.data().contact(side.tenantId());
        seed.data().conversation(side.tenantId(), contact, side.staffMembership());
        return contact;
    }

    /** What a fixture seeds with: admin SQL (bypassing RLS) and the shared test data helpers. */
    record Seeding(JdbcClient admin, TestData data) {

        UUID returningId(String sql, Map<String, ?> params) {
            JdbcClient.StatementSpec statement = admin.sql(sql);
            for (Map.Entry<String, ?> param : params.entrySet()) {
                statement = statement.param(param.getKey(), param.getValue());
            }
            return statement.query(UUID.class).single();
        }
    }

    private TenantFixtures() {}

    /** A new tenant with its admin and staff member. */
    static Side newSide(TestData data) {
        TestData.Member admin = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(admin.tenantId(), staffUser, "staff", "active");
        return new Side(admin.tenantId(), admin.userId(), admin.membershipId(), staffUser, staffMembership);
    }
}
