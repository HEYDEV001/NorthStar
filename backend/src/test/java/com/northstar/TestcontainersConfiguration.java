package com.northstar;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Supabase Postgres for integration tests: the same image the local Supabase CLI runs, so the auth schema,
 * auth.uid() and Supabase roles behave as they do in production. Never H2.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Keep in step with the image the Supabase CLI runs (docker images | grep supabase/postgres).
    static final DockerImageName SUPABASE_POSTGRES = DockerImageName.parse(
                    "public.ecr.aws/supabase/postgres:17.11.0.004")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new SupabasePostgresContainer();
    }

    /** Sets up the roles as postgres, then migrates as migrator, the same order every environment follows. */
    @Bean
    FlywayMigrationStrategy migrateAsMigrator(PostgreSQLContainer postgres) {
        return flyway -> {
            String migratorPassword = UUID.randomUUID().toString();
            try (Connection admin = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                DatabaseBootstrap.applyRoles(admin);
                DatabaseBootstrap.enableLogin(admin, "migrator", migratorPassword);
            } catch (SQLException e) {
                throw new IllegalStateException("Database bootstrap failed", e);
            }
            Flyway.configure()
                    .configuration(flyway.getConfiguration())
                    .dataSource(postgres.getJdbcUrl(), "migrator", migratorPassword)
                    .load()
                    .migrate();
        };
    }

    /**
     * The image initialises as supabase_admin and loads its config from /etc/postgresql; tests then connect as the
     * non-superuser postgres role, the same admin role operators get on hosted Supabase.
     */
    static class SupabasePostgresContainer extends PostgreSQLContainer {

        SupabasePostgresContainer() {
            super(SUPABASE_POSTGRES);
            withDatabaseName("postgres");
            withUsername("postgres");
            withPassword("postgres");
            withCommand("postgres", "-D", "/etc/postgresql", "-c", "fsync=off");
        }

        @Override
        protected void configure() {
            super.configure();
            addEnv("POSTGRES_USER", "supabase_admin");
        }
    }
}
