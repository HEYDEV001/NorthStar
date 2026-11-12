package com.northstar;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fixtures written as postgres, which bypasses RLS, so tests can set up several tenants at once. Random IDs and slugs
 * keep tests independent in the shared database.
 */
public final class TestData {

    private final JdbcClient admin;

    public TestData(PostgresAdmin postgres) {
        this.admin = postgres.jdbc();
    }

    public UUID tenant() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.tenants (id, name, slug) values (:id, 'Test business', :slug)")
                .param("id", id)
                .param("slug", "slug-" + id)
                .update();
        return id;
    }

    public UUID user() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.user_profiles (id) values (:id)")
                .param("id", id)
                .update();
        return id;
    }

    public UUID membership(UUID tenant, UUID user, String role, String status) {
        return admin.sql("""
                        insert into northstar.memberships (tenant_id, user_id, role, status)
                        values (:tenant, :user, :role, :status) returning id""")
                .param("tenant", tenant)
                .param("user", user)
                .param("role", role)
                .param("status", status)
                .query(UUID.class)
                .single();
    }

    /** A user with an active membership in a new tenant. */
    public Member member(String role) {
        UUID tenant = tenant();
        UUID user = user();
        UUID membership = membership(tenant, user, role, "active");
        return new Member(tenant, user, membership);
    }

    public record Member(UUID tenantId, UUID userId, UUID membershipId) {}

    /** A contact with a random E.164 phone number. */
    public UUID contact(UUID tenant) {
        return admin.sql("""
                        insert into northstar.contacts (tenant_id, normalized_phone)
                        values (:tenant, :phone) returning id""")
                .param("tenant", tenant)
                .param("phone", "+91" + (9_000_000_000L + (long) (Math.random() * 999_999_999L)))
                .query(UUID.class)
                .single();
    }

    /** A workflow with one published version (published by the system), returning the version's ID. */
    public UUID workflowVersion(UUID tenant) {
        UUID workflow = admin.sql("""
                        insert into northstar.workflows (tenant_id, name, template)
                        values (:tenant, 'Shoot production', 'shoot_production') returning id""").param("tenant", tenant).query(UUID.class).single();
        UUID version = admin.sql("""
                        insert into northstar.workflow_versions (tenant_id, workflow_id, version, config)
                        values (:tenant, :workflow, 1, '{"stages": [{"key": "qualifying"}]}') returning id""")
                .param("tenant", tenant)
                .param("workflow", workflow)
                .query(UUID.class)
                .single();
        admin.sql("update northstar.workflows set current_version_id = :version where id = :workflow")
                .param("version", version)
                .param("workflow", workflow)
                .update();
        return version;
    }

    /** An open lead for a new contact on the given version, assigned to the membership (null: unassigned). */
    public UUID lead(UUID tenant, UUID version, UUID assignedMembership) {
        return admin.sql("""
                        insert into northstar.leads
                          (tenant_id, contact_id, workflow_version_id, stage, origin, assigned_membership_id)
                        values (:tenant, :contact, :version, 'qualifying', 'direct', :assigned) returning id""")
                .param("tenant", tenant)
                .param("contact", contact(tenant))
                .param("version", version)
                .param("assigned", assignedMembership)
                .query(UUID.class)
                .single();
    }
}
