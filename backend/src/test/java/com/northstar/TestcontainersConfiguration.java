package com.northstar;

import com.northstar.fakes.HmacSigner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
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
    PostgreSQLContainer postgresContainer() {
        return new SupabasePostgresContainer();
    }

    /** The app connects as app_user, exactly as in every environment; fixtures use {@link PostgresAdmin}. */
    @Bean
    JdbcConnectionDetails appUserConnection(PostgreSQLContainer postgres, TestLogins logins) {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return "app_user";
            }

            @Override
            public String getPassword() {
                return logins.appUserPassword();
            }
        };
    }

    /** Throwaway passwords for this test run's migrator, app_user, jobrunr_user and secrets_user logins. */
    public record TestLogins(
            String migratorPassword, String appUserPassword, String jobRunrPassword, String secretsUserPassword) {}

    @Bean
    TestLogins testLogins() {
        return new TestLogins(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    /**
     * Email settings for this test run: a random invite token key and Resend key (never real), and a sender. The
     * worker tests need them; every other test sends through the fake email sender.
     */
    @Bean
    DynamicPropertyRegistrar testEmailSettings() {
        byte[] tokenKey = new byte[32];
        new java.security.SecureRandom().nextBytes(tokenKey);
        String encodedTokenKey = java.util.Base64.getEncoder().encodeToString(tokenKey);
        String resendKey = "re_test_" + UUID.randomUUID();
        return registry -> {
            registry.add("northstar.invites.token-key", () -> encodedTokenKey);
            registry.add("northstar.email.resend.api-key", () -> resendKey);
            registry.add("northstar.email.from", () -> "Northstar <invites@example.test>");
        };
    }

    /**
     * The WhatsApp webhook's secrets for this test run (step 2.2): Meta's app secret is the signer's own random secret,
     * and the verify token is random too; neither is ever a fixture. Tests sign deliveries with {@link #signer()}.
     */
    public record WhatsAppTestSecrets(HmacSigner signer, String verifyToken) {}

    @Bean
    WhatsAppTestSecrets whatsAppTestSecrets() {
        return new WhatsAppTestSecrets(
                new HmacSigner("X-Hub-Signature-256", "sha256="), UUID.randomUUID() + "-" + UUID.randomUUID());
    }

    /**
     * Registered under the environment variable names, which application.properties reads, rather than the
     * northstar.webhooks.* keys: dynamic properties outrank a test's own properties, so this way a test can still blank
     * the northstar.webhooks.* keys to see the app without them.
     */
    @Bean
    DynamicPropertyRegistrar whatsAppWebhookSecrets(WhatsAppTestSecrets secrets) {
        return registry -> {
            registry.add("NORTHSTAR_WHATSAPP_APP_SECRET", () -> secrets.signer().secret());
            registry.add("NORTHSTAR_WHATSAPP_VERIFY_TOKEN", secrets::verifyToken);
        };
    }

    /** JobRunr's private pool connects as jobrunr_user, exactly as in every environment. */
    @Bean
    DynamicPropertyRegistrar jobRunrConnection(PostgreSQLContainer postgres, TestLogins logins) {
        return registry -> {
            registry.add("northstar.jobs.database.url", postgres::getJdbcUrl);
            registry.add("northstar.jobs.database.username", () -> "jobrunr_user");
            registry.add("northstar.jobs.database.password", logins::jobRunrPassword);
        };
    }

    /**
     * The worker's credential pool connects as secrets_user (step 2.1b), with a random key per run (never real). Only
     * contexts with the worker on build the credential store; the rest never read these.
     */
    @Bean
    DynamicPropertyRegistrar credentialsConnection(PostgreSQLContainer postgres, TestLogins logins) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String keys = "test-1:" + java.util.Base64.getEncoder().encodeToString(key);
        return registry -> {
            registry.add("northstar.credentials.database.url", postgres::getJdbcUrl);
            registry.add("northstar.credentials.database.username", () -> "secrets_user");
            registry.add("northstar.credentials.database.password", logins::secretsUserPassword);
            registry.add("northstar.credentials.keys", () -> keys);
            registry.add("northstar.credentials.active-key", () -> "test-1");
        };
    }

    /** Sets up the roles as postgres, then migrates as migrator, the same order every environment follows. */
    @Bean
    FlywayMigrationStrategy migrateAsMigrator(PostgreSQLContainer postgres, TestLogins logins) {
        return flyway -> {
            try (Connection admin = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                DatabaseBootstrap.applyRoles(admin);
                DatabaseBootstrap.enableLogin(admin, "migrator", logins.migratorPassword());
                DatabaseBootstrap.enableLogin(admin, "app_user", logins.appUserPassword());
                DatabaseBootstrap.enableLogin(admin, "jobrunr_user", logins.jobRunrPassword());
                DatabaseBootstrap.enableLogin(admin, "secrets_user", logins.secretsUserPassword());
            } catch (SQLException e) {
                throw new IllegalStateException("Database bootstrap failed", e);
            }
            Flyway.configure()
                    .configuration(flyway.getConfiguration())
                    .dataSource(postgres.getJdbcUrl(), "migrator", logins.migratorPassword())
                    .load()
                    .migrate();
        };
    }

    @Bean
    @Primary
    AppUserDatabase appUserDatabase(PostgreSQLContainer postgres, TestLogins logins) {
        return new AppUserDatabase(postgres, "app_user", logins.appUserPassword());
    }

    /**
     * The worker's credential login (step 2.1a), the only role that may use the secrets schema. Inject it with
     * {@code @Qualifier("secretsUserDatabase")}.
     */
    @Bean
    AppUserDatabase secretsUserDatabase(PostgreSQLContainer postgres, TestLogins logins) {
        return new AppUserDatabase(postgres, "secrets_user", logins.secretsUserPassword());
    }

    @Bean
    PostgresAdmin postgresAdmin(PostgreSQLContainer postgres) {
        return new PostgresAdmin(postgres);
    }

    /** Signs test access tokens and serves their JWKS; the app verifies them exactly as it verifies Supabase's. */
    @Bean
    TestTokens testTokens() throws Exception {
        return new TestTokens();
    }

    @Bean
    DynamicPropertyRegistrar testTokenIssuer(TestTokens tokens) {
        return registry -> {
            registry.add("northstar.auth.issuer", tokens::issuer);
            registry.add("northstar.auth.jwk-set-uri", tokens::jwkSetUri);
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
