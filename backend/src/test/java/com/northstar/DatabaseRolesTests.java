package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Guards data-model.md section 12: the three roles, who owns northstar, and the default privileges. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseRolesTests {

    private static final String ROLE_POWERS = """
            select rolname || ' super=' || rolsuper || ' bypassrls=' || rolbypassrls
                   || ' createrole=' || rolcreaterole || ' createdb=' || rolcreatedb
            from pg_roles
            where rolname in ('migrator', 'app_user', 'app_admin', 'jobrunr_user')
            order by rolname""";

    private final PostgresAdmin postgres;
    private final JdbcClient jdbc;

    DatabaseRolesTests(@Autowired PostgresAdmin postgres) {
        this.postgres = postgres;
        this.jdbc = postgres.jdbc();
    }

    @Test
    void rolesHaveOnlyTheirIntendedPowers() {
        assertThat(jdbc.sql(ROLE_POWERS).query(String.class).list())
                .containsExactly(
                        "app_admin super=false bypassrls=true createrole=false createdb=false",
                        "app_user super=false bypassrls=false createrole=false createdb=false",
                        "jobrunr_user super=false bypassrls=false createrole=false createdb=false",
                        "migrator super=false bypassrls=true createrole=false createdb=false");
    }

    @Test
    void migratorOwnsTheSchemaAndEveryTableInIt() {
        String schemaOwner = jdbc.sql("select nspowner::regrole::text from pg_namespace where nspname = 'northstar'")
                .query(String.class)
                .single();
        List<String> tableOwners = jdbc.sql(
                        "select distinct tableowner::text from pg_tables where schemaname = 'northstar'")
                .query(String.class)
                .list();

        assertThat(schemaOwner).isEqualTo("migrator");
        assertThat(tableOwners).containsExactly("migrator");
    }

    @Test
    void appRolesCanUseButNotCreateInNorthstar() {
        List<String> privileges = jdbc.sql("""
                        select r || ' usage=' || has_schema_privilege(r, 'northstar', 'USAGE')
                               || ' create=' || has_schema_privilege(r, 'northstar', 'CREATE')
                        from unnest(array['app_admin', 'app_user']) r
                        order by r""").query(String.class).list();

        assertThat(privileges).containsExactly("app_admin usage=true create=false", "app_user usage=true create=false");
    }

    @Test
    void newTablesGrantAppUserReadWriteWithoutDeleteAndAppAdminReadOnly() {
        postgres.rolledBack(probe -> {
            probe.sql("set local role migrator").update();
            probe.sql("create table northstar.roles_probe (id bigint generated always as identity, note text)")
                    .update();

            List<String> tablePrivileges = probe.sql("""
                            select r || ':' || string_agg(p, ',' order by p)
                            from unnest(array['app_user', 'app_admin', 'anon', 'authenticated']) r,
                                 unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) p
                            where has_table_privilege(r, 'northstar.roles_probe', p)
                            group by r
                            order by r""").query(String.class).list();
            Boolean appUserCanUseIdentity = probe.sql(
                            "select has_sequence_privilege('app_user', pg_get_serial_sequence('northstar.roles_probe', 'id'), 'USAGE')")
                    .query(Boolean.class)
                    .single();

            assertThat(tablePrivileges).containsExactly("app_admin:SELECT", "app_user:INSERT,SELECT,UPDATE");
            assertThat(appUserCanUseIdentity).isTrue();
        });
    }

    @Test
    void appRolesCannotTouchMigrationHistory() {
        Boolean anyAccess = jdbc.sql("""
                        select bool_or(has_table_privilege(r, 'northstar.flyway_schema_history', p))
                        from unnest(array['app_user', 'app_admin']) r,
                             unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) p""").query(Boolean.class).single();
        assertThat(anyAccess).isFalse();
    }

    @Test
    void bootstrapIsSafeToRunAgain() throws SQLException {
        try (Connection admin = postgres.dataSource().getConnection()) {
            DatabaseBootstrap.applyRoles(admin);
        }

        assertThat(jdbc.sql(ROLE_POWERS).query(String.class).list()).hasSize(4);
        assertThat(jdbc.sql("""
                        select nspname || '=' || nspowner::regrole::text from pg_namespace
                        where nspname in ('northstar', 'jobrunr') order by nspname""").query(String.class).list()).containsExactly("jobrunr=migrator", "northstar=migrator");
        // Running it again leaves JobRunr's own access in place.
        assertThat(jdbc.sql("select has_table_privilege('jobrunr_user', 'jobrunr.jobrunr_jobs', 'SELECT, DELETE')")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }
}
