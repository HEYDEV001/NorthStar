package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Guards data-model.md section 1: every table lives in northstar, which the browser-facing roles cannot reach. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NorthstarSchemaTests {

    private final PostgresAdmin postgres;
    private final JdbcClient jdbc;

    NorthstarSchemaTests(@Autowired PostgresAdmin postgres) {
        this.postgres = postgres;
        this.jdbc = postgres.jdbc();
    }

    @Test
    void migrationsApplyWithHistoryInNorthstarNotPublic() {
        Integer applied = jdbc.sql(
                        "select count(*) from northstar.flyway_schema_history where success and version is not null")
                .query(Integer.class)
                .single();
        Boolean historyInPublic = jdbc.sql("select to_regclass('public.flyway_schema_history') is not null")
                .query(Boolean.class)
                .single();

        assertThat(applied).isPositive();
        assertThat(historyInPublic).isFalse();
    }

    @Test
    void browserRolesCannotUseNorthstarSchema() {
        Boolean anyUsage = jdbc.sql("select has_schema_privilege('anon', 'northstar', 'USAGE')"
                        + " or has_schema_privilege('authenticated', 'northstar', 'USAGE')")
                .query(Boolean.class)
                .single();
        assertThat(anyUsage).isFalse();
    }

    @Test
    void tablesCreatedLaterAreNotGrantedToBrowserRoles() {
        Boolean anyAccess = postgres.rolledBack(probe -> {
            probe.sql("set local role migrator").update();
            probe.sql("create table northstar.privilege_probe (id int)").update();
            return probe.sql("select has_table_privilege('anon', 'northstar.privilege_probe', 'SELECT')"
                            + " or has_table_privilege('authenticated', 'northstar.privilege_probe', 'SELECT')")
                    .query(Boolean.class)
                    .single();
        });
        assertThat(anyAccess).isFalse();
    }
}
