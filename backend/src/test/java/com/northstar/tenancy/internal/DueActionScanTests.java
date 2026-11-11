package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.TenantDirectory;
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
 * The sweeper's tenant scan (step 1.10): northstar.tenants_with_due_actions() returns tenant IDs only and is locked
 * down, and the guard's scan mode allows that one statement and nothing else.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DueActionScanTests {

    private final TenantDirectory directory;
    private final DataSource dataSource;
    private final TestData data;
    private final JdbcClient admin;

    DueActionScanTests(
            @Autowired TenantDirectory directory, @Autowired DataSource dataSource, @Autowired PostgresAdmin postgres) {
        this.directory = directory;
        this.dataSource = dataSource;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void listsOnlyTenantsWithDueActions() {
        UUID due = data.tenant();
        UUID leaseExpired = data.tenant();
        UUID later = data.tenant();
        UUID waitingForReview = data.tenant();
        action(due, "pending", "now() - interval '1 second'", "null");
        action(leaseExpired, "processing", "null", "now() - interval '1 second'");
        action(later, "pending", "now() + interval '1 hour'", "null");
        action(waitingForReview, "needs_review", "null", "null");

        assertThat(directory.tenantsWithDueActions())
                .contains(due, leaseExpired)
                .doesNotContain(later, waitingForReview);
    }

    @Test
    void theFunctionIsLockedDown() {
        assertThat(admin.sql("""
                        select pg_get_userbyid(p.proowner) || ',' || p.prosecdef || ',' || array_to_string(p.proconfig, ';')
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'tenants_with_due_actions'""").query(String.class).single())
                .isEqualTo("migrator,true,search_path=pg_catalog, pg_temp");
        assertThat(admin.sql("""
                        select string_agg(r, ',' order by r)
                        from unnest(array['app_user', 'app_admin', 'jobrunr_user', 'public', 'anon', 'authenticated']) r
                        where has_function_privilege(r, 'northstar.tenants_with_due_actions()', 'EXECUTE')""").query(String.class).single()).isEqualTo("app_user");
    }

    @Test
    void scanModeAllowsOnlyTheScan() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.unwrap(TenantGuardedConnection.class).beginDueActionScan();

            assertThatThrownBy(() -> connection.prepareStatement("select * from northstar.actions"))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.MEMBERSHIP_LOOKUP))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(connection::createStatement).isInstanceOf(MissingTenantContextException.class);

            connection
                    .prepareStatement(TenantGuardDataSource.DUE_ACTION_TENANTS)
                    .close();
            connection.rollback();

            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.DUE_ACTION_TENANTS))
                    .isInstanceOf(MissingTenantContextException.class);
            connection.setAutoCommit(true);
        }
    }

    private void action(UUID tenant, String status, String nextAttemptAt, String leaseExpiresAt) {
        admin.sql("""
                        insert into northstar.actions
                          (tenant_id, type, status, idempotency_key, mode, next_attempt_at, lease_expires_at)
                        values (:t, 'test.fake', :status, :key, 'automatic', %s, %s)""".formatted(nextAttemptAt, leaseExpiresAt))
                .param("t", tenant)
                .param("status", status)
                .param("key", "scan:" + UUID.randomUUID())
                .update();
    }
}
