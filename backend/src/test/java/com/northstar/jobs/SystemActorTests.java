package com.northstar.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.workflow.internal.SystemContextProbe;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The worker's system actor (step 1.10): role system with the nil user, made only in the jobs package, never inside a
 * web request, never stored in a profile or membership, and denied by the admin-only policies.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SystemActorTests {

    private final TestData data;
    private final JdbcClient admin;
    private final AppUserDatabase app;

    SystemActorTests(@Autowired PostgresAdmin postgres, @Autowired AppUserDatabase app) {
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
        this.app = app;
    }

    @Test
    void noHttpPathCanRunAsTheSystem() {
        SystemContextTestEndpoints.RAN_AS_SYSTEM.set(false);
        // No security filters and so no tenant scope: only the request itself stands in the way.
        MockMvcTester bare = MockMvcTester.of(new SystemContextTestEndpoints());

        assertThat(bare.post().uri("/test/run-as-system").exchange())
                .hasFailed()
                .failure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The system context cannot run inside a web request");
        assertThat(SystemContextTestEndpoints.RAN_AS_SYSTEM).isFalse();
    }

    @Test
    void aMembershipCanNeverGrantTheSystemRole() {
        assertThatThrownBy(() -> Role.fromDatabaseValue("system")).isInstanceOf(IllegalArgumentException.class);
        TestData.Member member = data.member("admin");
        assertThatThrownBy(() -> admin.sql("update northstar.memberships set role = 'system' where id = :id")
                        .param("id", member.membershipId())
                        .update())
                .hasMessageContaining("memberships_role_check");
    }

    @Test
    void theNilUserCanNeverBeARealUser() {
        UUID nil = TenantContext.SYSTEM_USER_ID;
        assertThatThrownBy(() -> admin.sql("insert into northstar.user_profiles (id) values (:id)")
                        .param("id", nil)
                        .update())
                .hasMessageContaining("user_profiles_id_not_nil");
        UUID tenant = data.tenant();
        assertThatThrownBy(
                        () -> admin.sql("""
                                insert into northstar.memberships (tenant_id, user_id, role, status)
                                values (:t, :u, 'staff', 'active')""").param("t", tenant).param("u", nil).update())
                .hasMessageContaining("memberships_user_id_not_nil");
    }

    @Test
    void theSystemRoleAndTheNilUserGoTogether() {
        UUID tenant = UUID.randomUUID();
        assertThat(TenantContext.system(tenant).isSystem()).isTrue(); // from this package: allowed
        assertThatThrownBy(() -> new TenantContext(tenant, UUID.randomUUID(), Role.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantContext(tenant, TenantContext.SYSTEM_USER_ID, Role.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheJobsPackageCanMakeTheSystemContext() {
        assertThatThrownBy(SystemContextProbe::tryToActAsSystem)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only background jobs");
    }

    @Test
    void theSystemIsDeniedByTheAdminOnlyPolicies() {
        TestData.Member member = data.member("admin");
        UUID other = data.user();
        assertThatThrownBy(() -> app.run(
                        member.tenantId(),
                        TenantContext.SYSTEM_USER_ID,
                        "system",
                        jdbc -> jdbc.sql("""
                                insert into northstar.memberships (tenant_id, user_id, role, status)
                                values (:t, :u, 'staff', 'deactivated')""")
                                .param("t", member.tenantId())
                                .param("u", other)
                                .update()))
                .rootCause()
                .hasMessageContaining("row-level security");
        int changed = app.as(
                member.tenantId(),
                TenantContext.SYSTEM_USER_ID,
                "system",
                jdbc -> jdbc.sql("update northstar.tenants set name = 'changed by a job' where id = :t")
                        .param("t", member.tenantId())
                        .update());
        assertThat(changed).as("tenant settings are admin-only").isZero();
    }
}
