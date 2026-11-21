package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.TenantDirectory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The webhook sweep's tenant scan (step 2.4a): northstar.tenants_with_unprocessed_webhooks() returns tenant IDs only,
 * for WhatsApp events of every kind (messages; statuses since 2.7; account-level changes since 2.8a) still unprocessed
 * past the grace period on the database's clock, and is locked down; the guard's scan mode allows that one statement
 * and nothing else (P10).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UnprocessedWebhookScanTests {

    private static final Duration GRACE = Duration.ofMinutes(2);

    private final TenantDirectory directory;
    private final DataSource dataSource;
    private final TestData data;
    private final JdbcClient admin;

    UnprocessedWebhookScanTests(
            @Autowired TenantDirectory directory, @Autowired DataSource dataSource, @Autowired PostgresAdmin postgres) {
        this.directory = directory;
        this.dataSource = dataSource;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void listsOnlyTenantsWithWhatsAppEventsOfAnyKindUnprocessedPastTheGracePeriod() {
        UUID waiting = data.tenant();
        UUID failedWaiting = data.tenant();
        UUID fresh = data.tenant();
        UUID processed = data.tenant();
        UUID statusOnly = data.tenant();
        UUID accountChangeOnly = data.tenant();
        UUID otherProvider = data.tenant();
        webhookEvent(waiting, "message:", "3 minutes", false, null);
        webhookEvent(failedWaiting, "message:", "1 day", false, "database_unavailable");
        webhookEvent(fresh, "message:", "0 seconds", false, null);
        webhookEvent(processed, "message:", "1 hour", true, null);
        webhookEvent(statusOnly, "status:", "1 hour", false, null);
        webhookEvent(accountChangeOnly, "business_capability_update:", "1 hour", false, null);
        admin.sql("""
                        insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload, received_at)
                        values (:t, 'razorpay', :key, '{}', now() - interval '1 hour')""")
                .param("t", otherProvider)
                .param("key", "pay_" + UUID.randomUUID())
                .update();

        assertThat(directory.tenantsWithUnprocessedWebhooks(GRACE))
                .as("status rows count since step 2.7, account-level changes since 2.8a; other providers do not")
                .contains(waiting, failedWaiting, statusOnly, accountChangeOnly)
                .doesNotContain(fresh, processed, otherProvider)
                .doesNotHaveDuplicates();
    }

    @Test
    void theGracePeriodIsMeasuredOnTheDatabasesClock() {
        UUID tenant = data.tenant();
        webhookEvent(tenant, "message:", "90 seconds", false, null);
        webhookEvent(tenant, "message:", "100 seconds", false, null);

        assertThat(directory.tenantsWithUnprocessedWebhooks(GRACE)).doesNotContain(tenant);
        assertThat(directory.tenantsWithUnprocessedWebhooks(Duration.ofMinutes(1)))
                .containsOnlyOnce(tenant);
        assertThatThrownBy(() -> directory.tenantsWithUnprocessedWebhooks(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFunctionIsLockedDown() {
        assertThat(admin.sql("""
                        select pg_get_userbyid(p.proowner) || ',' || p.prosecdef || ',' || array_to_string(p.proconfig, ';')
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'tenants_with_unprocessed_webhooks'""").query(String.class).single())
                .isEqualTo("migrator,true,search_path=pg_catalog, pg_temp");
        assertThat(admin.sql("""
                        select string_agg(r, ',' order by r)
                        from unnest(array['app_user', 'app_admin', 'jobrunr_user', 'secrets_user', 'public', 'anon',
                                          'authenticated']) r
                        where has_function_privilege(
                          r, 'northstar.tenants_with_unprocessed_webhooks(interval)', 'EXECUTE')""").query(String.class).single()).isEqualTo("app_user");
        assertThat(admin.sql("""
                        select pg_get_function_result(p.oid)
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'tenants_with_unprocessed_webhooks'""").query(String.class).single())
                .as("tenant IDs and nothing else")
                .isEqualTo("TABLE(tenant_id uuid)");
    }

    @Test
    void P10_theUnprocessedWebhookScanModeAllowsOnlyItsOneStatement() throws SQLException {
        UUID tenant = data.tenant();
        webhookEvent(tenant, "message:", "5 minutes", false, null);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.unwrap(TenantGuardedConnection.class).beginUnprocessedWebhookScan();

            assertThatThrownBy(() -> connection.prepareStatement("select * from northstar.webhook_events"))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.DUE_ACTION_TENANTS))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.MEMBERSHIP_LOOKUP))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(connection::createStatement).isInstanceOf(MissingTenantContextException.class);

            try (PreparedStatement scan =
                    connection.prepareStatement(TenantGuardDataSource.UNPROCESSED_WEBHOOK_TENANTS)) {
                scan.setString(1, GRACE.toString());
                boolean found = false;
                try (ResultSet rows = scan.executeQuery()) {
                    assertThat(rows.getMetaData().getColumnCount()).isEqualTo(1);
                    while (rows.next()) found |= tenant.equals(rows.getObject(1, UUID.class));
                }
                assertThat(found).isTrue();
            }
            connection.rollback();

            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.UNPROCESSED_WEBHOOK_TENANTS))
                    .as("the mode ends with its transaction")
                    .isInstanceOf(MissingTenantContextException.class);
            connection.setAutoCommit(true);
        }

        // The due-action scan's mode does not allow this scan either: each mode has exactly one statement.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.unwrap(TenantGuardedConnection.class).beginDueActionScan();
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.UNPROCESSED_WEBHOOK_TENANTS))
                    .isInstanceOf(MissingTenantContextException.class);
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    /** A WhatsApp webhook event of the given kind, received that long ago (a Postgres interval). */
    private void webhookEvent(UUID tenant, String kind, String age, boolean processed, String error) {
        admin.sql("""
                        insert into northstar.webhook_events
                          (tenant_id, provider, provider_event_id, payload, received_at, processed_at, error)
                        values (:t, 'whatsapp', :key, '{}', now() - cast(:age as interval),
                                case when :processed then now() end, :error)""")
                .param("t", tenant)
                .param("key", kind + "wamid.TEST" + UUID.randomUUID() + (kind.equals("status:") ? ":delivered" : ""))
                .param("age", age)
                .param("processed", processed)
                .param("error", error)
                .update();
    }
}
