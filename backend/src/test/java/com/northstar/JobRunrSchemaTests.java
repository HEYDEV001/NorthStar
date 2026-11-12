package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.jobrunr.storage.sql.common.DatabaseCreator;
import org.jobrunr.storage.sql.common.DatabaseMigrationsProvider;
import org.jobrunr.storage.sql.common.migrations.SqlMigration;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JobRunr's tables (step 1.10): our Flyway migration must build exactly the schema the pinned JobRunr version builds
 * itself, and record exactly its migration list, so a JobRunr upgrade without a new migration fails here. Also: the
 * login JobRunr connects with cannot read Northstar's tables (P10).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobRunrSchemaTests {

    private static final String REFERENCE = "jobrunr_reference";

    private final PostgresAdmin postgres;
    private final JdbcClient admin;
    private final PostgreSQLContainer container;
    private final TestcontainersConfiguration.TestLogins logins;

    JobRunrSchemaTests(
            @Autowired PostgresAdmin postgres,
            @Autowired PostgreSQLContainer container,
            @Autowired TestcontainersConfiguration.TestLogins logins) {
        this.postgres = postgres;
        this.admin = postgres.jdbc();
        this.container = container;
        this.logins = logins;
    }

    @Test
    void ourMigrationBuildsTheSchemaJobRunrBuilds() {
        admin.sql("create schema " + REFERENCE).update();
        try {
            new DatabaseCreator(postgres.dataSource(), REFERENCE + ".", PostgresStorageProvider.class).runMigrations();

            assertThat(columns("jobrunr")).isEqualTo(columns(REFERENCE)).isNotEmpty();
            assertThat(indexes("jobrunr")).isEqualTo(indexes(REFERENCE)).isNotEmpty();
            // The rows JobRunr's migrations seed. A running JobRunr adds its own (cluster ID, database version) and
            // moves the counter's value, so only the seeded rows' identity is compared.
            List<String> seeded = seededMetadata(REFERENCE);
            assertThat(seeded).isNotEmpty();
            assertThat(seededMetadata("jobrunr")).containsAll(seeded);
        } finally {
            admin.sql("drop schema " + REFERENCE + " cascade").update();
        }
    }

    @Test
    void ourMigrationRecordsJobRunrsMigrationList() {
        List<String> jobRunrs = new DatabaseMigrationsProvider(PostgresStorageProvider.class)
                .getMigrations()
                .map(SqlMigration::getFileName)
                .sorted()
                .toList();
        List<String> recorded = admin.sql("select script from jobrunr.jobrunr_migrations order by script")
                .query(String.class)
                .list();

        assertThat(recorded).isEqualTo(jobRunrs).isNotEmpty();
    }

    @Test
    void P10_jobRunrsLoginCannotReadNorthstarTables() throws SQLException {
        try (Connection jobRunr =
                        DriverManager.getConnection(container.getJdbcUrl(), "jobrunr_user", logins.jobRunrPassword());
                Statement statement = jobRunr.createStatement()) {
            statement.executeQuery("select count(*) from jobrunr.jobrunr_jobs").close();
            for (String table : List.of("northstar.tenants", "northstar.actions", "northstar.memberships")) {
                assertThatThrownBy(() -> statement.executeQuery("select * from " + table))
                        .as(table)
                        .hasMessageContaining("permission denied for schema northstar");
            }
        }
    }

    /** table.column type(length,precision,scale) null default, for every table and view in the schema. */
    private List<String> columns(String schema) {
        return admin.sql("""
                        select table_name || '.' || column_name || ' ' || data_type
                               || '(' || coalesce(character_maximum_length::text, '') || ','
                               || coalesce(numeric_precision::text, '') || ',' || coalesce(numeric_scale::text, '')
                               || ',' || coalesce(datetime_precision::text, '') || ') ' || is_nullable
                               || ' ' || coalesce(column_default, '')
                        from information_schema.columns
                        where table_schema = :schema
                        order by table_name, column_name""").param("schema", schema).query(String.class).list();
    }

    private List<String> indexes(String schema) {
        return admin.sql("""
                        select tablename || ' ' || indexname || ' '
                               || replace(indexdef, :schema || '.', '')
                        from pg_indexes where schemaname = :schema
                        order by tablename, indexname""").param("schema", schema).query(String.class).list();
    }

    private List<String> seededMetadata(String schema) {
        return admin.sql("select id || ' ' || name || ' ' || owner from " + schema + ".jobrunr_metadata order by id")
                .query(String.class)
                .list();
    }
}
