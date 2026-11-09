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
}
