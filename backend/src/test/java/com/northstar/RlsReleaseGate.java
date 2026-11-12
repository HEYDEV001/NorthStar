package com.northstar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The RLS release gate (data-model.md section 12): every rule a Northstar table, view or function must meet before it
 * can merge. {@link #violations} lists each broken rule as "schema.object: problem"; an empty list means the gate
 * passes.
 *
 * <p>Update the declarations in {@link #NORTHSTAR} in the same pull request as the migration that needs them.
 */
record RlsReleaseGate(
        List<String> schemas,
        Map<String, Set<String>> platformTables,
        Map<String, Set<String>> tenantTablePolicies,
        Set<String> insertOnlyTables,
        Map<String, Set<String>> lifecycleTables,
        Map<String, Set<String>> columnRestrictedUpdates,
        Set<String> securityDefinerFunctions,
        Map<String, String> isolatedSchemas) {

    static final RlsReleaseGate NORTHSTAR = new RlsReleaseGate(
            // Schemas the gate scans. Add ingest and secrets here if Phase 2 adopts them.
            List.of("northstar", "jobrunr"),
            // Platform-wide tables with no tenant_id, each with its exact RLS policies (see policy(...) for the
            // format). An empty set means no policies: the app roles have no access. plans joins when created.
            // JobRunr's tables have no RLS; only jobrunr_user may use them (see isolatedSchemas below).
            Map.of(
                    "northstar.flyway_schema_history", Set.of(),
                    "jobrunr.jobrunr_migrations", Set.of(),
                    "jobrunr.jobrunr_jobs", Set.of(),
                    "jobrunr.jobrunr_recurring_jobs", Set.of(),
                    "jobrunr.jobrunr_backgroundjobservers", Set.of(),
                    "jobrunr.jobrunr_metadata", Set.of(),
                    "northstar.tenants",
                            Set.of(
                                    policy(
                                            "tenant_select",
                                            "SELECT",
                                            "(id = (current_setting('app.tenant_id'::text, true))::uuid)",
                                            null),
                                    policy(
                                            "tenant_insert",
                                            "INSERT",
                                            null,
                                            "(id = (current_setting('app.tenant_id'::text, true))::uuid)"),
                                    policy(
                                            "tenant_update",
                                            "UPDATE",
                                            "((id = (current_setting('app.tenant_id'::text, true))::uuid)"
                                                    + " AND (current_setting('app.role'::text, true) = 'admin'::text))",
                                            "((id = (current_setting('app.tenant_id'::text, true))::uuid)"
                                                    + " AND (current_setting('app.role'::text, true) = 'admin'::text))")),
                    "northstar.user_profiles",
                            Set.of(
                                    policy(
                                            "profile_select",
                                            "SELECT",
                                            "((id = (current_setting('app.user_id'::text, true))::uuid) OR (EXISTS ("
                                                    + " SELECT FROM northstar.memberships m WHERE ((m.user_id ="
                                                    + " user_profiles.id) AND (m.tenant_id ="
                                                    + " (current_setting('app.tenant_id'::text, true))::uuid)))))",
                                            null),
                                    policy(
                                            "profile_insert",
                                            "INSERT",
                                            null,
                                            "(id = (current_setting('app.user_id'::text, true))::uuid)"),
                                    policy(
                                            "profile_update",
                                            "UPDATE",
                                            "(id = (current_setting('app.user_id'::text, true))::uuid)",
                                            "(id = (current_setting('app.user_id'::text, true))::uuid)"))),
            // Policies on tenant tables besides tenant_isolation. Each must be restrictive and match exactly.
            Map.of(
                    "northstar.memberships", adminOnlyWrites("memberships"),
                    "northstar.invites", adminOnlyWrites("invites"),
                    "northstar.leads", Set.of(staffSeeAssignedLeads())),
            // app_user may insert and read, never update or delete.
            Set.of(
                    "northstar.workflow_versions",
                    "northstar.timeline_events",
                    "northstar.audit_log",
                    "northstar.lead_score_history",
                    "northstar.credit_notes"),
            // app_user may never delete or update the whole row; only the listed status and processing columns are
            // updatable. Each list is filled in by the migration that creates the table.
            Map.of(
                    "northstar.actions",
                            Set.of(
                                    "status",
                                    "result",
                                    "attempts",
                                    "next_attempt_at",
                                    "lease_expires_at",
                                    "last_attempt_at",
                                    "last_error_class",
                                    "last_error_code",
                                    "approver_membership_id",
                                    "approved_at"),
                    "northstar.webhook_events", Set.of(),
                    "northstar.payments", Set.of(),
                    "northstar.invoices", Set.of(),
                    "northstar.refunds", Set.of()),
            // Tables app_user may update only in these columns, and never delete from. The declaration must match the
            // column grants exactly, so a migration that widens one fails the build.
            Map.of(
                    "northstar.memberships", Set.of("role", "status", "whatsapp_phone"),
                    "northstar.invites", Set.of("revoked_at"),
                    "northstar.tenants",
                            Set.of(
                                    "name",
                                    "industry",
                                    "timezone",
                                    "locale",
                                    "brand_tone",
                                    "legal_name",
                                    "gstin",
                                    "state_code",
                                    "billing_address",
                                    "approval_mode",
                                    "quiet_hours"),
                    "northstar.user_profiles", Set.of("full_name", "phone", "avatar_path")),
            // SECURITY DEFINER functions, as "schema.name(argument types)". Each must also pin search_path and be
            // executable by app_user only.
            Set.of(
                    "northstar.current_membership()",
                    "northstar.slug_is_taken(candidate text)",
                    "northstar.accept_invite(p_token_hash text, p_email text)",
                    "northstar.tenants_with_due_actions()",
                    "northstar.set_invite_token(p_invite_id uuid, p_token_hash text)"),
            // Schemas that belong to one login role: no other app or browser role may use the schema or anything in
            // it, and that role may use no other scanned schema.
            Map.of("jobrunr", "jobrunr_user"));

    /** Every role that must stay out of a schema it does not own (see isolatedSchemas). */
    private static final List<String> APP_AND_BROWSER_ROLES =
            List.of("app_user", "app_admin", "jobrunr_user", "anon", "authenticated", "public");

    /** The tenant_isolation expression exactly as Postgres stores it for the policy in data-model.md section 12. */
    static final String TENANT_ISOLATION_EXPRESSION =
            "(tenant_id = (current_setting('app.tenant_id'::text, true))::uuid)";

    /**
     * A permissive app_user policy as the gate compares it: name, command, and the using and with check expressions
     * exactly as Postgres stores them (null when absent), with whitespace collapsed.
     */
    static String policy(String name, String command, String using, String withCheck) {
        return describe(name, "PERMISSIVE", command, "{app_user}", using, withCheck);
    }

    /** A restrictive app_user policy, in the same format as {@link #policy}. */
    static String restrictivePolicy(String name, String command, String using, String withCheck) {
        return describe(name, "RESTRICTIVE", command, "{app_user}", using, withCheck);
    }

    /**
     * The restrictive policy on leads (P11, step 1.13): admins and the system actor see the tenant's leads; staff only
     * those assigned to their own active membership. The same expression guards reads and writes.
     */
    private static String staffSeeAssignedLeads() {
        String assigned = "((current_setting('app.role'::text, true) = ANY (ARRAY['admin'::text, 'system'::text]))"
                + " OR ((current_setting('app.role'::text, true) = 'staff'::text) AND (assigned_membership_id = ("
                + " SELECT m.id FROM northstar.memberships m WHERE ((m.tenant_id ="
                + " (current_setting('app.tenant_id'::text, true))::uuid) AND (m.user_id ="
                + " (NULLIF(current_setting('app.user_id'::text, true), ''::text))::uuid) AND (m.status ="
                + " 'active'::text))))))";
        return restrictivePolicy("leads_staff_assigned", "ALL", assigned, assigned);
    }

    /** The restrictive policies that limit insert, update and delete to app.role = 'admin'. */
    private static Set<String> adminOnlyWrites(String table) {
        String admin = "(current_setting('app.role'::text, true) = 'admin'::text)";
        return Set.of(
                restrictivePolicy(table + "_admin_insert", "INSERT", null, admin),
                restrictivePolicy(table + "_admin_update", "UPDATE", admin, admin),
                restrictivePolicy(table + "_admin_delete", "DELETE", admin, null));
    }

    List<String> violations(JdbcClient jdbc) {
        Set<String> violations = new TreeSet<>();
        checkTenantTables(jdbc, violations);
        checkTablesWithoutTenantId(jdbc, violations);
        checkTenantForeignKeys(jdbc, violations);
        checkViews(jdbc, violations);
        checkInsertOnlyTables(jdbc, violations);
        checkLifecycleTables(jdbc, violations);
        checkColumnRestrictedTables(jdbc, violations);
        checkSecurityDefinerFunctions(jdbc, violations);
        checkIsolatedSchemas(jdbc, violations);
        return new ArrayList<>(violations);
    }

    private void checkTenantTables(JdbcClient jdbc, Set<String> violations) {
        jdbc.sql("""
                        select n.nspname || '.' || c.relname as name, c.relrowsecurity, c.relforcerowsecurity,
                               a.attnotnull, a.atttypid = 'uuid'::regtype as is_uuid
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped
                        where n.nspname = any(string_to_array(:schemas, ',')) and c.relkind in ('r', 'p')""")
                .param("schemas", String.join(",", schemas))
                .query((rs, row) -> {
                    String table = rs.getString("name");
                    if (!rs.getBoolean("is_uuid")) violations.add(table + ": tenant_id is not uuid");
                    if (!rs.getBoolean("attnotnull")) violations.add(table + ": tenant_id is nullable");
                    if (!rs.getBoolean("relrowsecurity")) violations.add(table + ": RLS not enabled");
                    if (!rs.getBoolean("relforcerowsecurity")) violations.add(table + ": RLS not forced");
                    return table;
                })
                .list()
                .forEach(table -> checkTenantPolicies(jdbc, table, violations));
    }

    private void checkTenantPolicies(JdbcClient jdbc, String table, Set<String> violations) {
        Set<String> declared = tenantTablePolicies.getOrDefault(table, Set.of());
        Set<String> found = new TreeSet<>();
        boolean tenantIsolationFound = false;
        for (Map<String, Object> policy : policies(jdbc, table)) {
            if ("tenant_isolation".equals(policy.get("policyname"))) {
                tenantIsolationFound = true;
                boolean exact = "PERMISSIVE".equals(policy.get("permissive"))
                        && "ALL".equals(policy.get("cmd"))
                        && "{app_user}".equals(policy.get("roles"))
                        && TENANT_ISOLATION_EXPRESSION.equals(policy.get("qual"))
                        && TENANT_ISOLATION_EXPRESSION.equals(policy.get("with_check"));
                if (!exact) violations.add(table + ": tenant_isolation policy differs from data-model.md section 12");
            } else if ("PERMISSIVE".equals(policy.get("permissive"))) {
                violations.add(table + ": policy " + policy.get("policyname") + " is permissive, must be restrictive");
            } else {
                String actual = describe(policy);
                found.add(actual);
                if (!declared.contains(actual)) violations.add(table + ": undeclared or changed policy: " + actual);
            }
        }
        if (!tenantIsolationFound) violations.add(table + ": tenant_isolation policy missing");
        for (String policy : declared) {
            if (!found.contains(policy)) violations.add(table + ": declared policy missing: " + policy);
        }
    }

    private void checkTablesWithoutTenantId(JdbcClient jdbc, Set<String> violations) {
        jdbc
                .sql("""
                        select n.nspname || '.' || c.relname as name, c.relrowsecurity, c.relforcerowsecurity
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = any(string_to_array(:schemas, ',')) and c.relkind in ('r', 'p')
                          and not exists (select from pg_attribute a
                                          where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)""")
                .param("schemas", String.join(",", schemas))
                .query((rs, row) -> {
                    String table = rs.getString("name");
                    Set<String> declaredPolicies = platformTables.get(table);
                    if (declaredPolicies == null) {
                        violations.add(table + ": no tenant_id and not declared platform-wide");
                    } else if (!declaredPolicies.isEmpty()) {
                        if (!rs.getBoolean("relrowsecurity")) violations.add(table + ": RLS not enabled");
                        if (!rs.getBoolean("relforcerowsecurity")) violations.add(table + ": RLS not forced");
                    }
                    return table;
                })
                .list()
                .stream()
                .filter(platformTables::containsKey)
                .forEach(table -> checkPlatformPolicies(jdbc, table, violations));
    }

    private void checkPlatformPolicies(JdbcClient jdbc, String table, Set<String> violations) {
        Set<String> declared = platformTables.get(table);
        Set<String> actual = new TreeSet<>();
        for (Map<String, Object> policy : policies(jdbc, table)) {
            actual.add(describe(policy));
        }
        for (String policy : actual) {
            if (!declared.contains(policy)) violations.add(table + ": undeclared or changed policy: " + policy);
        }
        for (String policy : declared) {
            if (!actual.contains(policy)) violations.add(table + ": declared policy missing: " + policy);
        }
    }

    /** A foreign key between two tables that both carry tenant_id must pair tenant_id with tenant_id. */
    private void checkTenantForeignKeys(JdbcClient jdbc, Set<String> violations) {
        jdbc.sql("""
                        select n.nspname || '.' || c.relname || ': foreign key ' || con.conname || ' to '
                               || pn.nspname || '.' || pc.relname || ' does not include tenant_id'
                        from pg_constraint con
                        join pg_class c on c.oid = con.conrelid
                        join pg_namespace n on n.oid = c.relnamespace
                        join pg_class pc on pc.oid = con.confrelid
                        join pg_namespace pn on pn.oid = pc.relnamespace
                        where con.contype = 'f' and n.nspname = any(string_to_array(:schemas, ','))
                          and exists (select from pg_attribute
                                      where attrelid = con.conrelid and attname = 'tenant_id' and not attisdropped)
                          and exists (select from pg_attribute
                                      where attrelid = con.confrelid and attname = 'tenant_id' and not attisdropped)
                          and not exists (
                                select from unnest(con.conkey, con.confkey) as k(child_attnum, parent_attnum)
                                join pg_attribute ca on ca.attrelid = con.conrelid and ca.attnum = k.child_attnum
                                join pg_attribute pa on pa.attrelid = con.confrelid and pa.attnum = k.parent_attnum
                                where ca.attname = 'tenant_id' and pa.attname = 'tenant_id')""")
                .param("schemas", String.join(",", schemas))
                .query(String.class)
                .list()
                .forEach(violations::add);
    }

    private void checkViews(JdbcClient jdbc, Set<String> violations) {
        jdbc.sql("""
                        select n.nspname || '.' || c.relname as name, c.relkind = 'm' as materialized,
                               coalesce('security_invoker=true' = any(c.reloptions), false) as invoker
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = any(string_to_array(:schemas, ',')) and c.relkind in ('v', 'm')""")
                .param("schemas", String.join(",", schemas))
                .query((rs, row) -> {
                    String view = rs.getString("name");
                    if (rs.getBoolean("materialized")) {
                        violations.add(view + ": materialized view bypasses RLS");
                    } else if (!rs.getBoolean("invoker")) {
                        violations.add(view + ": view without security_invoker bypasses RLS");
                    }
                    return view;
                })
                .list();
    }

    private void checkInsertOnlyTables(JdbcClient jdbc, Set<String> violations) {
        for (String table : insertOnlyTables) {
            for (String privilege : appUserPrivileges(jdbc, table)) {
                violations.add(table + ": insert-only but app_user has " + privilege);
            }
        }
    }

    private void checkLifecycleTables(JdbcClient jdbc, Set<String> violations) {
        lifecycleTables.forEach(
                (table, declared) -> checkUpdatableColumns(jdbc, table, declared, "lifecycle", false, violations));
    }

    private void checkColumnRestrictedTables(JdbcClient jdbc, Set<String> violations) {
        columnRestrictedUpdates.forEach((table, declared) ->
                checkUpdatableColumns(jdbc, table, declared, "column-restricted", true, violations));
    }

    /**
     * No delete, truncate or table-level update for app_user, and no updatable column outside the declaration. With
     * exact, every declared column must also be updatable, so the declaration and the grants cannot drift apart.
     */
    private static void checkUpdatableColumns(
            JdbcClient jdbc, String table, Set<String> declared, String tier, boolean exact, Set<String> violations) {
        if (!exists(jdbc, table)) return;
        for (String privilege : appUserPrivileges(jdbc, table)) {
            if (!privilege.equals("UPDATE on a column")) {
                violations.add(table + ": " + tier + " table but app_user has " + privilege);
            }
        }
        List<String> updatable =
                jdbc.sql("""
                        select attname from pg_attribute
                        where attrelid = cast(:table as regclass) and attnum > 0 and not attisdropped
                          and has_column_privilege('app_user', attrelid, attname, 'UPDATE')""").param("table", table).query(String.class).list();
        updatable.stream()
                .filter(column -> !declared.contains(column))
                .forEach(column -> violations.add(table + ": column " + column + " is updatable but not declared"));
        if (exact) {
            declared.stream()
                    .filter(column -> !updatable.contains(column))
                    .forEach(column -> violations.add(table + ": declared column " + column + " is not updatable"));
        }
    }

    /** app_user's write privileges beyond insert: table-level UPDATE, DELETE, TRUNCATE, and column-level UPDATE. */
    private static List<String> appUserPrivileges(JdbcClient jdbc, String table) {
        if (!exists(jdbc, table)) return List.of();
        return jdbc.sql("""
                        select p from unnest(array['UPDATE', 'DELETE', 'TRUNCATE']) p
                        where has_table_privilege('app_user', cast(:table as regclass), p)
                        union all
                        select 'UPDATE on a column'
                        where not has_table_privilege('app_user', cast(:table as regclass), 'UPDATE')
                          and has_any_column_privilege('app_user', cast(:table as regclass), 'UPDATE')""").param("table", table).query(String.class).list();
    }

    /**
     * SECURITY DEFINER functions run with their owner's rights (migrator bypasses RLS), so each must be allowlisted,
     * pin its search_path, and be executable by no browser-facing role.
     */
    private void checkSecurityDefinerFunctions(JdbcClient jdbc, Set<String> violations) {
        jdbc.sql("""
                        select n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                                   as name,
                               exists (select from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%')
                                   as pins_search_path,
                               has_function_privilege('public', p.oid, 'EXECUTE')
                                   or has_function_privilege('anon', p.oid, 'EXECUTE')
                                   or has_function_privilege('authenticated', p.oid, 'EXECUTE') as browser_can_execute
                        from pg_proc p
                        join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = any(string_to_array(:schemas, ',')) and p.prosecdef""")
                .param("schemas", String.join(",", schemas))
                .query((rs, row) -> {
                    String function = rs.getString("name");
                    if (!securityDefinerFunctions.contains(function)) {
                        violations.add(function + ": SECURITY DEFINER function not on the allowlist");
                    }
                    if (!rs.getBoolean("pins_search_path")) {
                        violations.add(function + ": SECURITY DEFINER function does not pin search_path");
                    }
                    if (rs.getBoolean("browser_can_execute")) {
                        violations.add(function + ": SECURITY DEFINER function is executable by public, anon or"
                                + " authenticated");
                    }
                    return function;
                })
                .list();
    }

    /**
     * An isolated schema is used only by its role: every other app or browser role has no privilege on the schema or
     * on any table or view in it, and the role has none on the other scanned schemas or anything in them.
     */
    private void checkIsolatedSchemas(JdbcClient jdbc, Set<String> violations) {
        isolatedSchemas.forEach((schema, owner) -> {
            for (String role : APP_AND_BROWSER_ROLES) {
                if (!role.equals(owner)) checkNoAccess(jdbc, role, schema, violations);
            }
            for (String other : schemas) {
                if (!other.equals(schema)) checkNoAccess(jdbc, owner, other, violations);
            }
        });
    }

    private static void checkNoAccess(JdbcClient jdbc, String role, String schema, Set<String> violations) {
        boolean usesSchema = jdbc.sql("""
                        select has_schema_privilege(:role, :schema, 'USAGE')
                               or has_schema_privilege(:role, :schema, 'CREATE')""")
                .param("role", role)
                .param("schema", schema)
                .query(Boolean.class)
                .single();
        if (usesSchema) violations.add(schema + ": " + role + " may use this schema");
        jdbc.sql("""
                        select n.nspname || '.' || c.relname
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = :schema and c.relkind in ('r', 'p', 'v', 'm')
                          and has_table_privilege(:role, c.oid,
                                                  'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER')""")
                .param("role", role)
                .param("schema", schema)
                .query(String.class)
                .list()
                .forEach(table -> violations.add(table + ": " + role + " has privileges here"));
    }

    private static List<Map<String, Object>> policies(JdbcClient jdbc, String table) {
        return jdbc.sql("""
                        select policyname, permissive, cmd, roles::text as roles, qual, with_check
                        from pg_policies
                        where schemaname || '.' || tablename = :table""").param("table", table).query().listOfRows();
    }

    private static boolean exists(JdbcClient jdbc, String table) {
        return jdbc.sql("select to_regclass(:table) is not null")
                .param("table", table)
                .query(Boolean.class)
                .single();
    }

    private static String describe(Map<String, Object> policy) {
        return describe(
                (String) policy.get("policyname"),
                (String) policy.get("permissive"),
                (String) policy.get("cmd"),
                (String) policy.get("roles"),
                (String) policy.get("qual"),
                (String) policy.get("with_check"));
    }

    private static String describe(
            String name, String mode, String command, String roles, String using, String withCheck) {
        return normalize(name + " " + mode + " " + command + " to " + roles + " using " + (using == null ? "-" : using)
                + " check " + (withCheck == null ? "-" : withCheck));
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
