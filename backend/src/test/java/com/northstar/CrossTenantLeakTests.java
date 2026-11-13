package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The cross-tenant release gate (data-model.md section 12, step 1.14): two tenants, one row of every tenant table each,
 * and for every role (admin, staff, the system actor, and no context at all) tenant A sees nothing of tenant B and can
 * change nothing of it, through the real app_user login with RLS. Driven by the catalogue: every table with tenant_id
 * in the gate's schemas is checked, and a table without a fixture fails the suite by name.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossTenantLeakTests {

    private final PostgresAdmin postgres;
    private final JdbcClient admin;
    private final AppUserDatabase app;
    private final TestData data;

    private TenantFixtures.Side a;
    private TenantFixtures.Side b;
    private final Map<String, UUID> rowsOfA = new HashMap<>();
    private final Map<String, UUID> rowsOfB = new HashMap<>();

    CrossTenantLeakTests(@Autowired PostgresAdmin postgres, @Autowired AppUserDatabase app) {
        this.postgres = postgres;
        this.admin = postgres.jdbc();
        this.app = app;
        this.data = new TestData(postgres);
    }

    /** Who runs the queries in tenant A: the role and the user it runs as. */
    private record Actor(String role, UUID user) {}

    @BeforeAll
    void seedTwoTenants() {
        a = TenantFixtures.newSide(data);
        b = TenantFixtures.newSide(data);
        TenantFixtures.Seeding seeding = new TenantFixtures.Seeding(admin, data);
        for (String table : tenantTables()) {
            var fixture = TenantFixtures.FIXTURES.get(table);
            if (fixture == null) continue; // reported by P9_everyTenantTableHasAFixture
            rowsOfA.put(table, fixture.apply(a, seeding));
            rowsOfB.put(table, fixture.apply(b, seeding));
        }
    }

    @Test
    void P9_everyTenantTableHasAFixture() {
        TreeSet<String> missing = new TreeSet<>(tenantTables());
        missing.removeAll(TenantFixtures.FIXTURES.keySet());
        TreeSet<String> stale = new TreeSet<>(TenantFixtures.FIXTURES.keySet());
        stale.removeAll(tenantTables());

        assertThat(missing)
                .as("tenant tables with no fixture in TenantFixtures")
                .isEmpty();
        assertThat(stale).as("fixtures for tables that no longer exist").isEmpty();
    }

    @Test
    void P9_adminsAndTheSystemSeeAndChangeNothingOfAnotherTenant() {
        List<String> leaks = new ArrayList<>();
        leaks.addAll(leaksFor(new Actor("admin", a.adminUser())));
        leaks.addAll(leaksFor(new Actor("system", TenantContext.SYSTEM_USER_ID)));
        assertThat(leaks).isEmpty();
    }

    @Test
    void P11_staffSeeAndChangeNothingOfAnotherTenant() {
        assertThat(leaksFor(new Actor("staff", a.staffUser()))).isEmpty();
    }

    @Test
    void P10_withoutAnyContextNoTenantTableShowsOrChangesAnything() {
        List<String> leaks = new ArrayList<>();
        for (String table : seededTables()) {
            long visible = app.withoutContext(jdbc -> count(jdbc, "select count(*) from " + table));
            if (visible != 0) leaks.add(table + ": " + visible + " rows visible without a tenant context");
            String write = attemptWrites(table, app::withoutContext, "true");
            if (write != null) leaks.add(table + " without context: " + write);
            String insert = attemptInsertIntoB(table, app::withoutContext);
            if (insert != null) leaks.add(table + " without context: " + insert);
        }
        long tenants = app.withoutContext(jdbc -> count(jdbc, "select count(*) from northstar.tenants"));
        long profiles = app.withoutContext(jdbc -> count(jdbc, "select count(*) from northstar.user_profiles"));
        if (tenants != 0) leaks.add("northstar.tenants: " + tenants + " rows visible without a tenant context");
        if (profiles != 0) leaks.add("northstar.user_profiles: " + profiles + " rows visible without a context");
        assertThat(leaks).isEmpty();
    }

    @Test
    void P9_platformTablesShowNothingOfAnotherTenant() {
        List<String> leaks = new ArrayList<>();
        for (Actor actor : actors()) {
            String who = actor.role();
            if (asA(
                            actor,
                            jdbc -> count(
                                    jdbc, "select count(*) from northstar.tenants where id = '" + a.tenantId() + "'"))
                    != 1) {
                leaks.add("northstar.tenants: " + who + " cannot see its own tenant (positive control)");
            }
            if (asA(
                            actor,
                            jdbc -> count(
                                    jdbc, "select count(*) from northstar.tenants where id <> '" + a.tenantId() + "'"))
                    != 0) {
                leaks.add("northstar.tenants: " + who + " sees another tenant");
            }
            if (asA(
                            actor,
                            jdbc -> count(
                                    jdbc,
                                    "select count(*) from northstar.user_profiles where id = '" + a.adminUser() + "'"))
                    != 1) {
                leaks.add("northstar.user_profiles: " + who + " cannot see its own tenant's member (positive control)");
            }
            if (asA(
                            actor,
                            jdbc -> count(
                                    jdbc,
                                    "select count(*) from northstar.user_profiles where id in ('" + b.adminUser()
                                            + "', '" + b.staffUser() + "')"))
                    != 0) {
                leaks.add("northstar.user_profiles: " + who + " sees another tenant's members");
            }
            String update = attempt(
                    jdbc -> jdbc.sql("update northstar.tenants set name = name where id = :b")
                            .param("b", b.tenantId())
                            .update(),
                    work -> asA(actor, work));
            if (update != null) leaks.add("northstar.tenants: " + who + " " + update);
        }
        assertThat(leaks).isEmpty();
    }

    // The checks, for every seeded tenant table.

    /** Everything this actor in tenant A can see or do to tenant B; empty when nothing leaks. */
    private List<String> leaksFor(Actor actor) {
        List<String> leaks = new ArrayList<>();
        for (String table : seededTables()) {
            String who = table + " as " + actor.role() + ": ";
            long own = asA(
                    actor,
                    jdbc -> count(jdbc, "select count(*) from " + table + " where id = '" + rowsOfA.get(table) + "'"));
            if (own != 1) leaks.add(who + "cannot see its own tenant's row (positive control: is RLS evaluating?)");
            long foreign = asA(
                    actor,
                    jdbc -> count(
                            jdbc, "select count(*) from " + table + " where tenant_id <> '" + a.tenantId() + "'"));
            if (foreign != 0) leaks.add(who + "sees " + foreign + " rows of other tenants");
            long bRow = asA(
                    actor,
                    jdbc -> count(jdbc, "select count(*) from " + table + " where id = '" + rowsOfB.get(table) + "'"));
            if (bRow != 0) leaks.add(who + "sees tenant B's row");
            String write = attemptWrites(table, work -> asA(actor, work), "tenant_id = '" + b.tenantId() + "'");
            if (write != null) leaks.add(who + write);
            String insert = attemptInsertIntoB(table, work -> asA(actor, work));
            if (insert != null) leaks.add(who + insert);
        }
        return leaks;
    }

    /** Update and delete of tenant B's rows must match nothing or be refused. Null when they are. */
    private String attemptWrites(String table, Function<Function<JdbcClient, Integer>, Integer> as, String where) {
        String column = updatableColumn(table);
        if (column != null) {
            String update = attempt(
                    jdbc -> jdbc.sql("update " + table + " set " + column + " = " + column + " where " + where)
                            .update(),
                    as);
            if (update != null) return "update " + update;
        }
        String delete = attempt(
                jdbc -> jdbc.sql("delete from " + table + " where " + where).update(), as);
        return delete == null ? null : "delete " + delete;
    }

    /** A copy of tenant A's row, moved to tenant B with a new id, must be refused. Null when it is. */
    private String attemptInsertIntoB(String table, Function<Function<JdbcClient, Integer>, Integer> as) {
        String row = admin.sql("select (to_jsonb(x) || jsonb_build_object('tenant_id', :b::text, 'id',"
                        + " gen_random_uuid()::text))::text from " + table + " x where x.id = :id")
                .param("b", b.tenantId().toString())
                .param("id", rowsOfA.get(table))
                .query(String.class)
                .single();
        try {
            int inserted = as.apply(jdbc -> jdbc.sql("insert into " + table
                            + " select * from json_populate_record(null::" + table + ", cast(:row as json))")
                    .param("row", row)
                    .update());
            return "insert into tenant B was accepted (" + inserted + " row)";
        } catch (RuntimeException refused) {
            String message = rootMessage(refused);
            boolean byRlsOrPrivilege = message.contains("row-level security") || message.contains("permission denied");
            return byRlsOrPrivilege ? null : "insert into tenant B failed for another reason: " + message;
        }
    }

    /** A write that matched rows is a leak; one refused for privileges or RLS is fine. Null when nothing leaked. */
    private static String attempt(
            Function<JdbcClient, Integer> write, Function<Function<JdbcClient, Integer>, Integer> as) {
        try {
            int changed = as.apply(write);
            return changed == 0 ? null : "changed " + changed + " rows of tenant B";
        } catch (RuntimeException refused) {
            String message = rootMessage(refused);
            boolean byRlsOrPrivilege = message.contains("permission denied") || message.contains("row-level security");
            return byRlsOrPrivilege ? null : "failed for another reason: " + message;
        }
    }

    // Helpers.

    private <T> T asA(Actor actor, Function<JdbcClient, T> work) {
        return app.as(a.tenantId(), actor.user(), actor.role(), work);
    }

    private List<Actor> actors() {
        return List.of(
                new Actor("admin", a.adminUser()),
                new Actor("staff", a.staffUser()),
                new Actor("system", TenantContext.SYSTEM_USER_ID));
    }

    /** Every table with tenant_id in the schemas the RLS release gate scans. */
    private List<String> tenantTables() {
        return admin.sql("""
                        select n.nspname || '.' || c.relname
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = any(string_to_array(:schemas, ',')) and c.relkind in ('r', 'p')
                          and exists (select from pg_attribute a
                                      where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
                        order by 1""")
                .param("schemas", String.join(",", RlsReleaseGate.NORTHSTAR.schemas()))
                .query(String.class)
                .list();
    }

    private List<String> seededTables() {
        return tenantTables().stream().filter(rowsOfA::containsKey).toList();
    }

    /** A column app_user may update, or null if none (then the update is refused outright). */
    private String updatableColumn(String table) {
        return admin.sql("""
                        select attname from pg_attribute
                        where attrelid = cast(:table as regclass) and attnum > 0 and not attisdropped
                          and has_column_privilege('app_user', attrelid, attname, 'UPDATE')
                        order by attnum limit 1""")
                .param("table", table)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static long count(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        return String.valueOf(root.getMessage());
    }
}
