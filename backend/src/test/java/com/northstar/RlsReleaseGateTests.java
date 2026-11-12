package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The release gate on the real schema, plus one deliberately broken probe per rule to prove the gate catches it.
 * Probes are created as migrator inside an admin transaction that is rolled back after each test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RlsReleaseGateTests {

    private static final String PROBE = "northstar.gate_probe";
    private static final String TENANT_ISOLATION_POLICY = """
            create policy tenant_isolation on northstar.gate_probe for all to app_user
              using (tenant_id = current_setting('app.tenant_id', true)::uuid)
              with check (tenant_id = current_setting('app.tenant_id', true)::uuid)""";

    private final PostgresAdmin postgres;
    private final JdbcClient jdbc;
    private PostgresAdmin.RollbackOnly probeTransaction;

    RlsReleaseGateTests(@Autowired PostgresAdmin postgres) {
        this.postgres = postgres;
        this.jdbc = postgres.jdbc();
    }

    @BeforeEach
    void openProbeTransaction() {
        probeTransaction = postgres.beginRollbackOnly();
    }

    @AfterEach
    void rollBackProbes() {
        probeTransaction.close();
    }

    @Test
    void northstarSchemasPassTheGate() {
        assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc)).isEmpty();
    }

    @Nested
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
        void aSecondPermissivePolicyOnLeadsFails() {
            sql("create policy leads_everyone on northstar.leads for select to app_user using (true)");
            assertThat(gate())
                    .containsExactly("northstar.leads: policy leads_everyone is permissive, must be restrictive");
        }

        @Test
        void aWeakenedStaffPolicyOnLeadsFails() {
            sql("drop policy leads_staff_assigned on northstar.leads");
            sql("create policy leads_staff_assigned on northstar.leads as restrictive for all to app_user"
                    + " using (true) with check (true)");
            assertThat(gate())
                    .hasSize(2)
                    .anySatisfy(v -> assertThat(v).startsWith("northstar.leads: undeclared or changed policy"))
                    .anySatisfy(v -> assertThat(v).startsWith("northstar.leads: declared policy missing"));
        }

        @Test
        void declaredRestrictivePolicyPasses() {
            createTenantProbe();
            sql("create policy staff_view on " + PROBE + " as restrictive for select to app_user using (id > 0)");
            assertThat(gateDeclaring(STAFF_VIEW)).isEmpty();
        }

        @Test
        void undeclaredRestrictivePolicyFails() {
            createTenantProbe();
            sql("create policy staff_view on " + PROBE + " as restrictive for select to app_user using (id > 0)");
            assertThat(gate()).containsExactly(PROBE + ": undeclared or changed policy: " + STAFF_VIEW);
        }

        @Test
        void changedRestrictivePolicyFails() {
            createTenantProbe();
            sql("create policy staff_view on " + PROBE + " as restrictive for select to app_user using (id > 1)");
            assertThat(gateDeclaring(STAFF_VIEW))
                    .containsExactly(
                            PROBE + ": declared policy missing: " + STAFF_VIEW,
                            PROBE + ": undeclared or changed policy: " + STAFF_VIEW.replace("(id > 0)", "(id > 1)"));
        }

        @Test
        void declaredRestrictivePolicyThatIsMissingFails() {
            createTenantProbe();
            assertThat(gateDeclaring(STAFF_VIEW)).containsExactly(PROBE + ": declared policy missing: " + STAFF_VIEW);
        }

        private static final String STAFF_VIEW =
                RlsReleaseGate.restrictivePolicy("staff_view", "SELECT", "(id > 0)", null);

        private List<String> gateDeclaring(String policy) {
            Map<String, Set<String>> tenantTablePolicies =
                    new HashMap<>(RlsReleaseGate.NORTHSTAR.tenantTablePolicies());
            tenantTablePolicies.put(PROBE, Set.of(policy));
            RlsReleaseGate northstar = RlsReleaseGate.NORTHSTAR;
            return new RlsReleaseGate(
                            northstar.schemas(),
                            northstar.platformTables(),
                            tenantTablePolicies,
                            northstar.insertOnlyTables(),
                            northstar.lifecycleTables(),
                            northstar.columnRestrictedUpdates(),
                            northstar.securityDefinerFunctions(),
                            northstar.isolatedSchemas())
                    .violations(jdbc);
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
            assertThat(gateWith(Map.of(PROBE, Set.of()), Set.of(), Map.of(), Set.of()))
                    .isEmpty();
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
            createLockedDownDefinerFunction();
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly("northstar.gate_fn(x integer): SECURITY DEFINER function not on the allowlist");
            assertThat(gateWith(Map.of(), Set.of(), Map.of(), Set.of("northstar.gate_fn(x integer)")))
                    .isEmpty();
        }

        @Test
        void securityDefinerFunctionMustPinSearchPath() {
            createLockedDownDefinerFunction();
            sql("alter function northstar.gate_fn(int) reset search_path");
            assertThat(gateWith(Map.of(), Set.of(), Map.of(), Set.of("northstar.gate_fn(x integer)")))
                    .containsExactly(
                            "northstar.gate_fn(x integer): SECURITY DEFINER function does not pin search_path");
        }

        @Test
        void securityDefinerFunctionMustNotBeExecutableByBrowserRoles() {
            createLockedDownDefinerFunction();
            sql("grant execute on function northstar.gate_fn(int) to authenticated");
            assertThat(gateWith(Map.of(), Set.of(), Map.of(), Set.of("northstar.gate_fn(x integer)")))
                    .containsExactly("northstar.gate_fn(x integer): SECURITY DEFINER function is executable by public,"
                            + " anon or authenticated");
        }

        @Test
        void securityDefinerFunctionLeftAtDefaultsFailsEveryRule() {
            sql("create function northstar.gate_fn(x int) returns int language sql security definer as 'select x'");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly(
                            "northstar.gate_fn(x integer): SECURITY DEFINER function does not pin search_path",
                            "northstar.gate_fn(x integer): SECURITY DEFINER function is executable by public, anon or"
                                    + " authenticated",
                            "northstar.gate_fn(x integer): SECURITY DEFINER function not on the allowlist");
        }

        private void createLockedDownDefinerFunction() {
            sql("create function northstar.gate_fn(x int) returns int language sql security definer"
                    + " set search_path = pg_catalog, pg_temp as 'select x'");
            sql("revoke all on function northstar.gate_fn(int) from public, anon, authenticated");
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
            assertThat(new RlsReleaseGate(
                                    List.of("northstar", "gate_other"),
                                    Map.of(),
                                    Map.of(),
                                    Set.of(),
                                    Map.of(),
                                    Map.of(),
                                    Set.of(),
                                    Map.of())
                            .violations(jdbc))
                    .contains("gate_other.t: no tenant_id and not declared platform-wide");
        }

        @Test
        void anotherRoleOnTheJobRunrSchemaFails() {
            sql("grant select on jobrunr.jobrunr_jobs to app_user");
            sql("grant usage on schema jobrunr to app_admin");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly(
                            "jobrunr.jobrunr_jobs: app_user has privileges here",
                            "jobrunr: app_admin may use this schema");
        }

        @Test
        void jobRunrRoleOnNorthstarFails() {
            sql("grant usage on schema northstar to jobrunr_user");
            sql("grant select on northstar.tenants to jobrunr_user");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly(
                            "northstar.tenants: jobrunr_user has privileges here",
                            "northstar: jobrunr_user may use this schema");
        }
    }

    @Nested
    class PlatformTables {

        private static final String SELF_ONLY = "(id = (current_setting('app.tenant_id'::text, true))::uuid)";

        @BeforeEach
        void createProbeAsMigrator() {
            sql("set local role migrator");
            sql("create table " + PROBE + " (id uuid primary key)");
            sql("alter table " + PROBE + " enable row level security");
            sql("alter table " + PROBE + " force row level security");
            sql("create policy probe_select on " + PROBE
                    + " for select to app_user using (id = current_setting('app.tenant_id', true)::uuid)");
        }

        @Test
        void policiesMatchingTheDeclarationPass() {
            assertThat(gateDeclaring(RlsReleaseGate.policy("probe_select", "SELECT", SELF_ONLY, null)))
                    .isEmpty();
        }

        @Test
        void changedPolicyFails() {
            sql("drop policy probe_select on " + PROBE);
            sql("create policy probe_select on " + PROBE + " for select to app_user using (true)");
            assertThat(gateDeclaring(RlsReleaseGate.policy("probe_select", "SELECT", SELF_ONLY, null)))
                    .containsExactly(
                            PROBE + ": declared policy missing: probe_select PERMISSIVE SELECT to {app_user} using "
                                    + SELF_ONLY + " check -",
                            PROBE + ": undeclared or changed policy: probe_select PERMISSIVE SELECT to {app_user}"
                                    + " using true check -");
        }

        @Test
        void extraPolicyFails() {
            sql("create policy probe_insert on " + PROBE + " for insert to app_user with check (true)");
            assertThat(gateDeclaring(RlsReleaseGate.policy("probe_select", "SELECT", SELF_ONLY, null)))
                    .containsExactly(PROBE + ": undeclared or changed policy: probe_insert PERMISSIVE INSERT to"
                            + " {app_user} using - check true");
        }

        @Test
        void rlsNotForcedFails() {
            sql("alter table " + PROBE + " no force row level security");
            assertThat(gateDeclaring(RlsReleaseGate.policy("probe_select", "SELECT", SELF_ONLY, null)))
                    .containsExactly(PROBE + ": RLS not forced");
        }

        private List<String> gateDeclaring(String policy) {
            return gateWith(Map.of(PROBE, Set.of(policy)), Set.of(), Map.of(), Set.of());
        }
    }

    @Nested
    class TenantForeignKeys {

        @BeforeEach
        void createParentAsMigrator() {
            sql("set local role migrator");
            createTenantTable(
                    "northstar.gate_parent", "id uuid primary key, tenant_id uuid not null, unique (tenant_id, id)");
        }

        @Test
        void compositeForeignKeyPasses() {
            createTenantTable(
                    PROBE,
                    "id int, tenant_id uuid not null, parent_id uuid,"
                            + " foreign key (tenant_id, parent_id) references northstar.gate_parent (tenant_id, id)");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc)).isEmpty();
        }

        @Test
        void foreignKeyWithoutTenantIdFails() {
            createTenantTable(
                    PROBE,
                    "id int, tenant_id uuid not null,"
                            + " parent_id uuid constraint probe_parent_fk references northstar.gate_parent (id)");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc))
                    .containsExactly(PROBE + ": foreign key probe_parent_fk to northstar.gate_parent does not include"
                            + " tenant_id");
        }

        @Test
        void foreignKeyToAPlatformTableNeedsNoTenantId() {
            createTenantTable(PROBE, "id int, tenant_id uuid not null references northstar.tenants (id)");
            assertThat(RlsReleaseGate.NORTHSTAR.violations(jdbc)).isEmpty();
        }

        private void createTenantTable(String table, String columns) {
            sql("create table " + table + " (" + columns + ")");
            sql("alter table " + table + " enable row level security");
            sql("alter table " + table + " force row level security");
            sql("create policy tenant_isolation on " + table + " for all to app_user"
                    + " using (tenant_id = current_setting('app.tenant_id', true)::uuid)"
                    + " with check (tenant_id = current_setting('app.tenant_id', true)::uuid)");
        }
    }

    @Nested
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

        @Test
        void columnRestrictedTableWithTheExactColumnsPasses() {
            sql("revoke update on " + PROBE + " from app_user");
            sql("grant update (status) on " + PROBE + " to app_user");
            assertThat(columnRestrictedGate()).isEmpty();
        }

        @Test
        void columnRestrictedTableWithAWiderGrantFails() {
            assertThat(columnRestrictedGate())
                    .containsExactly(
                            PROBE + ": column amount_paise is updatable but not declared",
                            PROBE + ": column id is updatable but not declared",
                            PROBE + ": column-restricted table but app_user has UPDATE");
        }

        @Test
        void columnRestrictedTableMissingADeclaredColumnFails() {
            sql("revoke update on " + PROBE + " from app_user");
            assertThat(columnRestrictedGate()).containsExactly(PROBE + ": declared column status is not updatable");
        }

        @Test
        void columnRestrictedTableWithDeleteFails() {
            sql("revoke update on " + PROBE + " from app_user");
            sql("grant update (status) on " + PROBE + " to app_user");
            sql("grant delete on " + PROBE + " to app_user");
            assertThat(columnRestrictedGate())
                    .containsExactly(PROBE + ": column-restricted table but app_user has DELETE");
        }

        private List<String> columnRestrictedGate() {
            Map<String, Set<String>> platformTables = new HashMap<>(RlsReleaseGate.NORTHSTAR.platformTables());
            platformTables.put(PROBE, Set.of());
            Map<String, Set<String>> restricted = new HashMap<>(RlsReleaseGate.NORTHSTAR.columnRestrictedUpdates());
            restricted.put(PROBE, Set.of("status"));
            RlsReleaseGate northstar = RlsReleaseGate.NORTHSTAR;
            return new RlsReleaseGate(
                            northstar.schemas(),
                            platformTables,
                            northstar.tenantTablePolicies(),
                            northstar.insertOnlyTables(),
                            northstar.lifecycleTables(),
                            restricted,
                            northstar.securityDefinerFunctions(),
                            northstar.isolatedSchemas())
                    .violations(jdbc);
        }

        private List<String> insertOnlyGate() {
            return gateWith(Map.of(PROBE, Set.of()), Set.of(PROBE), Map.of(), Set.of());
        }

        private List<String> lifecycleGate() {
            return gateWith(Map.of(PROBE, Set.of()), Set.of(), Map.of(PROBE, Set.of("status")), Set.of());
        }
    }

    private List<String> gateWith(
            Map<String, Set<String>> platformTables,
            Set<String> insertOnlyTables,
            Map<String, Set<String>> lifecycleTables,
            Set<String> securityDefinerFunctions) {
        Map<String, Set<String>> allPlatformTables = new HashMap<>(RlsReleaseGate.NORTHSTAR.platformTables());
        allPlatformTables.putAll(platformTables);
        Set<String> allSecurityDefinerFunctions = new HashSet<>(RlsReleaseGate.NORTHSTAR.securityDefinerFunctions());
        allSecurityDefinerFunctions.addAll(securityDefinerFunctions);
        return new RlsReleaseGate(
                        RlsReleaseGate.NORTHSTAR.schemas(),
                        allPlatformTables,
                        RlsReleaseGate.NORTHSTAR.tenantTablePolicies(),
                        insertOnlyTables,
                        lifecycleTables,
                        RlsReleaseGate.NORTHSTAR.columnRestrictedUpdates(),
                        allSecurityDefinerFunctions,
                        RlsReleaseGate.NORTHSTAR.isolatedSchemas())
                .violations(jdbc);
    }

    private void sql(String statement) {
        jdbc.sql(statement).update();
    }
}
