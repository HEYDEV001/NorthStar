package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The login-time membership lookup (step 1.5): northstar.current_membership() returns only the caller's active
 * membership and is locked down, and the guard's lookup mode allows that one statement and nothing else.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MembershipLookupTests {

    private final MembershipLookup lookup;
    private final MembershipRepository memberships;
    private final DataSource dataSource;
    private final JdbcClient admin;

    MembershipLookupTests(
            @Autowired MembershipLookup lookup,
            @Autowired MembershipRepository memberships,
            @Autowired DataSource dataSource,
            @Autowired PostgresAdmin postgres) {
        this.lookup = lookup;
        this.memberships = memberships;
        this.dataSource = dataSource;
        this.admin = postgres.jdbc();
    }

    @Test
    void returnsTheUsersActiveMembership() {
        UUID tenant = tenant();
        UUID user = user();
        UUID membership = membership(tenant, user, "admin", "active");
        membership(tenant(), user, "staff", "deactivated");

        assertThat(lookup.activeMembershipOf(user))
                .contains(new MembershipLookup.ActiveMembership(tenant, membership, Role.ADMIN));
    }

    @Test
    void returnsNothingForInvitedDeactivatedOrUnknownUsers() {
        UUID invited = user();
        UUID deactivated = user();
        membership(tenant(), invited, "staff", "invited");
        membership(tenant(), deactivated, "staff", "deactivated");

        assertThat(lookup.activeMembershipOf(invited)).isEmpty();
        assertThat(lookup.activeMembershipOf(deactivated)).isEmpty();
        assertThat(lookup.activeMembershipOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void neverReturnsAnotherUsersMembership() {
        UUID someoneElse = user();
        membership(tenant(), someoneElse, "admin", "active");

        assertThat(lookup.activeMembershipOf(user())).isEmpty();
    }

    @Test
    void returnsNothingWithoutAUserId() {
        membership(tenant(), user(), "admin", "active");
        assertThat(admin.sql("select count(*) from northstar.current_membership()")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void functionIsLockedDown() {
        String shape = admin.sql("""
                        select pg_get_userbyid(p.proowner) || ',' || p.prosecdef || ',' || p.pronargs || ','
                               || array_to_string(p.proconfig, ';')
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'current_membership'""").query(String.class).single();
        String executable = admin.sql("""
                        select string_agg(r, ',' order by r)
                        from unnest(array['app_user', 'app_admin', 'public', 'anon', 'authenticated']) r
                        where has_function_privilege(r, 'northstar.current_membership()', 'EXECUTE')""").query(String.class).single();

        assertThat(shape).isEqualTo("migrator,true,0,search_path=pg_catalog, pg_temp");
        assertThat(executable).isEqualTo("app_user");
    }

    @Test
    void lookupModeAllowsOnlyTheLookup() throws SQLException {
        UUID user = user();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.unwrap(TenantGuardedConnection.class).beginMembershipLookup(user);

            assertThatThrownBy(() -> connection.prepareStatement("select 1"))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement("select * from northstar.memberships"))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(connection::createStatement).isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareCall(TenantGuardDataSource.MEMBERSHIP_LOOKUP))
                    .isInstanceOf(MissingTenantContextException.class);

            connection.prepareStatement(TenantGuardDataSource.MEMBERSHIP_LOOKUP).close();
            connection.rollback();

            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.MEMBERSHIP_LOOKUP))
                    .isInstanceOf(MissingTenantContextException.class);
            connection.setAutoCommit(true);
        }
    }

    @Test
    void nothingElseRunsBetweenTheLookupAndTheTenantScope() {
        UUID user = user();
        membership(tenant(), user, "admin", "active");

        assertThat(lookup.activeMembershipOf(user)).isPresent();
        assertThatThrownBy(memberships::findAll).isInstanceOf(MissingTenantContextException.class);
    }

    // Fixtures, written as postgres (bypasses RLS).

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.tenants (id, name, slug) values (:id, 'Test business', :slug)")
                .param("id", id)
                .param("slug", "slug-" + id)
                .update();
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.user_profiles (id) values (:id)")
                .param("id", id)
                .update();
        return id;
    }

    private UUID membership(UUID tenant, UUID user, String role, String status) {
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
}
