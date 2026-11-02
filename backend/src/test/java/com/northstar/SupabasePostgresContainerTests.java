package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Guards the test database choice: RLS tests depend on Supabase's auth schema and roles being present. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SupabasePostgresContainerTests {

    private final JdbcClient jdbc;

    SupabasePostgresContainerTests(@Autowired JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void runsPostgres17() {
        Integer major = jdbc.sql("select current_setting('server_version_num')::int / 10000")
                .query(Integer.class)
                .single();
        assertThat(major).isEqualTo(17);
    }

    @Test
    void connectsAsNonSuperuserPostgresLikeHostedSupabase() {
        Boolean superuser = jdbc.sql(
                        "select rolsuper from pg_roles where rolname = current_user and current_user = 'postgres'")
                .query(Boolean.class)
                .single();
        assertThat(superuser).isFalse();
    }

    @Test
    void hasSupabaseAuthSchemaAndUidFunction() {
        Boolean present = jdbc.sql("select to_regprocedure('auth.uid()') is not null")
                .query(Boolean.class)
                .single();
        assertThat(present).isTrue();
    }

    @Test
    void hasSupabaseApiRoles() {
        Integer roles = jdbc.sql(
                        "select count(*) from pg_roles where rolname in ('anon', 'authenticated', 'service_role')")
                .query(Integer.class)
                .single();
        assertThat(roles).isEqualTo(3);
    }
}
