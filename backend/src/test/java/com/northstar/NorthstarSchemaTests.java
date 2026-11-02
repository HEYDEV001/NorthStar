package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Guards data-model.md section 1: every table lives in northstar, which the browser-facing roles cannot reach. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NorthstarSchemaTests {

    private final JdbcClient jdbc;

    NorthstarSchemaTests(@Autowired JdbcClient jdbc) {
        this.jdbc = jdbc;
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
    @Transactional
    void tablesCreatedLaterAreNotGrantedToBrowserRoles() {
        jdbc.sql("create table northstar.privilege_probe (id int)").update();

        Boolean anyAccess = jdbc.sql("select has_table_privilege('anon', 'northstar.privilege_probe', 'SELECT')"
                        + " or has_table_privilege('authenticated', 'northstar.privilege_probe', 'SELECT')")
                .query(Boolean.class)
                .single();
        assertThat(anyAccess).isFalse();
    }
}
