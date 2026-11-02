package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The release gate on the real schema, plus one deliberately broken probe per rule to prove the gate catches it.
 * Probes are created as migrator inside a transaction that is rolled back after each test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RlsReleaseGateTests {

    private static final String PROBE = "northstar.gate_probe";
    private static final String TENANT_ISOLATION_POLICY = """
            create policy tenant_isolation on northstar.gate_probe for all to app_user
              using (tenant_id = current_setting('app.tenant_id', true)::uuid)
              with check (tenant_id = current_setting('app.tenant_id', true)::uuid)""";

    private final JdbcClient jdbc;

    RlsReleaseGateTests(@Autowired JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void northstarSchemasPassTheGate() {
        assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc)).isEmpty();
    }

    @Nested
    @Transactional
    class TenantTables {

        @BeforeEach
        void actAsMigrator() {
            sql("set local role migrator");
        }

        @Test
        void tableBuiltToSpecPasses() {
            createTenantProbe();
            assertThat(gate()).isEmpty();
        }

        @Test
        void rlsNotEnabledFails() {
            sql("create table " + PROBE + " (id int, tenant_id uuid not null)");
            sql(TENANT_ISOLATION_POLICY);
            assertThat(gate()).containsExactly(PROBE + ": RLS not enabled", PROBE + ": RLS not forced");
        }

        @Test
        void rlsEnabledButNotForcedFails() {
            sql("create table " + PROBE + " (id int, tenant_id uuid not null)");
            sql("alter table " + PROBE + " enable row level security");
            sql(TENANT_ISOLATION_POLICY);
            assertThat(gate()).containsExactly(PROBE + ": RLS not forced");
        }

        @Test
        void nullableTenantIdFails() {
            createTenantProbe();
            sql("alter table " + PROBE + " alter column tenant_id drop not null");
            assertThat(gate()).containsExactly(PROBE + ": tenant_id is nullable");
        }

        @Test
        void tenantIdThatIsNotUuidFails() {
            sql("create table " + PROBE + " (id int, tenant_id text not null)");
            sql("alter table " + PROBE + " enable row level security");
            sql("alter table " + PROBE + " force row level security");
            assertThat(gate())
                    .containsExactly(PROBE + ": tenant_id is not uuid", PROBE + ": tenant_isolation policy missing");
        }

        @Test
        void missingPolicyFails() {
            createTenantProbe();
            sql("drop policy tenant_isolation on " + PROBE);
            assertThat(gate()).containsExactly(PROBE + ": tenant_isolation policy missing");
        }

        @Test
        void weakenedPolicyFails() {
            createTenantProbe();
            sql("drop policy tenant_isolation on " + PROBE);
            sql("create policy tenant_isolation on " + PROBE + " for all to app_user using (true)");
            assertThat(gate())
                    .containsExactly(PROBE + ": tenant_isolation policy differs from data-model.md section 12");
        }

        @Test
        void policyForTheWrongRoleFails() {
            createTenantProbe();
            sql("drop policy tenant_isolation on " + PROBE);
            sql(TENANT_ISOLATION_POLICY.replace("to app_user", "to public"));
            assertThat(gate())
                    .containsExactly(PROBE + ": tenant_isolation policy differs from data-model.md section 12");
        }

        @Test
        void extraPermissivePolicyFails() {
            createTenantProbe();
            sql("create policy staff_view on " + PROBE + " for select to app_user using (true)");
            assertThat(gate()).containsExactly(PROBE + ": policy staff_view is permissive, must be restrictive");
        }

        @Test
        void extraRestrictivePolicyPasses() {
            createTenantProbe();
            sql("create policy staff_view on " + PROBE + " as restrictive for select to app_user using (id > 0)");
            assertThat(gate()).isEmpty();
        }

        private void createTenantProbe() {
            sql("create table " + PROBE + " (id int, tenant_id uuid not null)");
            sql("alter table " + PROBE + " enable row level security");
            sql("alter table " + PROBE + " force row level security");
            sql(TENANT_ISOLATION_POLICY);
        }

        private List<String> gate() {
            return RlsReleaseGate.NORTHSTAR.violations(jdbc);
        }
    }

    @Nested
    @Transactional
    class OtherObjects {

        @BeforeEach
        void actAsMigrator() {
            sql("set local role migrator");
        }

        @Test
        void tableWithoutTenantIdMustBeDeclaredPlatformWide() {
            sql("create table " + PROBE + " (id int)");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly(PROBE + ": no tenant_id and not declared platform-wide");
            assertThat(gateWith(Set.of(PROBE), Set.of(), Map.of(), Set.of())).isEmpty();
        }

        @Test
        void viewWithoutSecurityInvokerFails() {
            sql("create view northstar.gate_view as select 1 as x");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly("northstar.gate_view: view without security_invoker bypasses RLS");
        }

        @Test
        void viewWithSecurityInvokerPasses() {
            sql("create view northstar.gate_view with (security_invoker = true) as select 1 as x");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc)).isEmpty();
        }

        @Test
        void materializedViewFails() {
            sql("create materialized view northstar.gate_view as select 1 as x");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly("northstar.gate_view: materialized view bypasses RLS");
        }

        @Test
        void securityDefinerFunctionMustBeAllowlisted() {
            sql("create function northstar.gate_fn(x int) returns int language sql security definer as 'select x'");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly("northstar.gate_fn(x integer): SECURITY DEFINER function not on the allowlist");
            assertThat(gateWith(Set.of(), Set.of(), Map.of(), Set.of("northstar.gate_fn(x integer)")))
                    .isEmpty();
        }

        @Test
        void securityInvokerFunctionNeedsNoAllowlist() {
            sql("create function northstar.gate_fn(x int) returns int language sql as 'select x'");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc)).isEmpty();
        }

        @Test
        void schemasOutsideTheDeclaredListAreNotScanned() {
            sql("reset role");
            sql("create schema gate_other");
            sql("create table gate_other.t (id int)");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc)).isEmpty();
            assertThat(new RlsReleaseGate(List.of("northstar", "gate_other"), Set.of(), Set.of(), Map.of(), Set.of())
                            .violations(jdbc))
                    .contains("gate_other.t: no tenant_id and not declared platform-wide");
        }
    }

    @Nested
    @Transactional
    class AppendOnlyTables {

        @BeforeEach
        void createProbeAsMigrator() {
            sql("set local role migrator");
            sql("create table " + PROBE + " (id int, status text, amount_paise bigint)");
        }

        @Test
        void insertOnlyTableWithDefaultUpdateFails() {
            assertThat(insertOnlyGate()).containsExactly(PROBE + ": insert-only but app_user has UPDATE");
        }

        @Test
        void insertOnlyTableWithUpdateRevokedPasses() {
            sql("revoke update on " + PROBE + " from app_user");
            assertThat(insertOnlyGate()).isEmpty();
        }

        @Test
        void insertOnlyTableWithColumnUpdateFails() {
            sql("revoke update on " + PROBE + " from app_user");
            sql("grant update (status) on " + PROBE + " to app_user");
            assertThat(insertOnlyGate()).containsExactly(PROBE + ": insert-only but app_user has UPDATE on a column");
        }

        @Test
        void insertOnlyTableWithDeleteFails() {
            sql("revoke update on " + PROBE + " from app_user");
            sql("grant delete, truncate on " + PROBE + " to app_user");
            assertThat(insertOnlyGate())
                    .containsExactly(
                            PROBE + ": insert-only but app_user has DELETE",
                            PROBE + ": insert-only but app_user has TRUNCATE");
        }

        @Test
        void lifecycleTableWithTableLevelUpdateFails() {
            assertThat(lifecycleGate())
                    .containsExactly(
                            PROBE + ": column amount_paise is updatable but not declared",
                            PROBE + ": column id is updatable but not declared",
                            PROBE + ": lifecycle table but app_user has UPDATE");
        }

        @Test
        void lifecycleTableWithOnlyDeclaredColumnsPasses() {
            sql("revoke update on " + PROBE + " from app_user");
            sql("grant update (status) on " + PROBE + " to app_user");
            assertThat(lifecycleGate()).isEmpty();
        }

        @Test
        void lifecycleTableWithUndeclaredColumnFails() {
            sql("revoke update on " + PROBE + " from app_user");
            sql("grant update (status, amount_paise) on " + PROBE + " to app_user");
            assertThat(lifecycleGate()).containsExactly(PROBE + ": column amount_paise is updatable but not declared");
        }

        @Test
        void lifecycleTableWithDeleteFails() {
            sql("revoke update on " + PROBE + " from app_user");
            sql("grant update (status) on " + PROBE + " to app_user");
            sql("grant delete on " + PROBE + " to app_user");
            assertThat(lifecycleGate()).containsExactly(PROBE + ": lifecycle table but app_user has DELETE");
        }

        private List<String> insertOnlyGate() {
            return gateWith(Set.of(PROBE), Set.of(PROBE), Map.of(), Set.of());
        }

        private List<String> lifecycleGate() {
            return gateWith(Set.of(PROBE), Set.of(), Map.of(PROBE, Set.of("status")), Set.of());
        }
    }

    private List<String> gateWith(
            Set<String> platformTables,
            Set<String> insertOnlyTables,
            Map<String, Set<String>> lifecycleTables,
            Set<String> securityDefinerFunctions) {
        Set<String> allPlatformTables = new HashSet<>(RlsReleaseGate.NORTHSTAR.platformTables());
        allPlatformTables.addAll(platformTables);
        return new RlsReleaseGate(
                        RlsReleaseGate.NORTHSTAR.schemas(),
                        allPlatformTables,
                        insertOnlyTables,
                        lifecycleTables,
                        securityDefinerFunctions)
                .violations(jdbc);
    }

    private void sql(String statement) {
        jdbc.sql(statement).update();
    }
}
