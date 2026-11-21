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
 * The scheduled WhatsApp sync's tenant scan (step 2.8a): northstar.tenants_with_whatsapp() lists the tenants whose
 * WhatsApp is connected with a credential and who have a number, returns tenant IDs only and is locked down, and the
 * guard's scan mode allows that one statement and nothing else (P10).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WhatsAppTenantScanTests {

    private final TenantDirectory directory;
    private final DataSource dataSource;
    private final TestData data;
    private final JdbcClient admin;

    WhatsAppTenantScanTests(
            @Autowired TenantDirectory directory, @Autowired DataSource dataSource, @Autowired PostgresAdmin postgres) {
        this.directory = directory;
        this.dataSource = dataSource;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void listsOnlyTenantsWithWhatsAppConnectedAndANumber() {
        UUID connected = data.tenant();
        data.whatsAppNumber(connected);
        data.whatsAppNumber(connected);
        integration(connected, "connected", true);
        UUID noNumber = data.tenant();
        integration(noNumber, "connected", true);
        UUID inError = data.tenant();
        data.whatsAppNumber(inError);
        integration(inError, "error", true);
        UUID disconnected = data.tenant();
        data.whatsAppNumber(disconnected);
        integration(disconnected, "disconnected", true);
        UUID noCredential = data.tenant();
        data.whatsAppNumber(noCredential);
        integration(noCredential, "connected", false);
        UUID neverConnected = data.tenant();
        data.whatsAppNumber(neverConnected);

        assertThat(directory.tenantsWithWhatsApp())
                .containsOnlyOnce(connected)
                .doesNotContain(noNumber, inError, disconnected, noCredential, neverConnected);
    }

    @Test
    void theFunctionIsLockedDownAndReturnsTenantIdsOnly() {
        assertThat(admin.sql("""
                        select pg_get_userbyid(p.proowner) || ',' || p.prosecdef || ',' || array_to_string(p.proconfig, ';')
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'tenants_with_whatsapp'""").query(String.class).single())
                .isEqualTo("migrator,true,search_path=pg_catalog, pg_temp");
        assertThat(admin.sql("""
                        select string_agg(r, ',' order by r)
                        from unnest(array['app_user', 'app_admin', 'jobrunr_user', 'secrets_user', 'public', 'anon',
                                          'authenticated']) r
                        where has_function_privilege(r, 'northstar.tenants_with_whatsapp()', 'EXECUTE')""").query(String.class).single()).isEqualTo("app_user");
        assertThat(admin.sql("""
                        select pg_get_function_result(p.oid)
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'tenants_with_whatsapp'""").query(String.class).single())
                .as("tenant IDs and nothing else")
                .isEqualTo("TABLE(tenant_id uuid)");
    }

    @Test
    void P10_scanModeAllowsOnlyTheScan() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.unwrap(TenantGuardedConnection.class).beginWhatsAppTenantScan();

            assertThatThrownBy(() -> connection.prepareStatement("select * from northstar.integrations"))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.DUE_ACTION_TENANTS))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.WHATSAPP_WABA_ROUTING))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(connection::createStatement).isInstanceOf(MissingTenantContextException.class);

            connection.prepareStatement(TenantGuardDataSource.WHATSAPP_TENANTS).close();
            connection.rollback();

            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.WHATSAPP_TENANTS))
                    .isInstanceOf(MissingTenantContextException.class);
            connection.setAutoCommit(true);
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() ->
                            connection.unwrap(TenantGuardedConnection.class).beginWhatsAppTenantScan())
                    .as("outside a transaction")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private void integration(UUID tenant, String status, boolean withCredential) {
        admin.sql("""
                        insert into northstar.integrations (tenant_id, provider, status, credential_id)
                        values (:t, 'whatsapp', :status, :credential)""")
                .param("t", tenant)
                .param("status", status)
                .param("credential", withCredential ? data.credential(tenant) : null)
                .update();
    }
}
