package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.TestcontainersConfiguration;
import com.northstar.TestcontainersConfiguration.TestLogins;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.TenantDirectory;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * How a gate tool starts the app's services (step 2.11): the real ones, never a test's fake; no job server, no business
 * token, no migration and no open port; and only against the database in .env.local, whatever else is set.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LocalAppContextTests {

    private final PostgreSQLContainer postgres;
    private final TestLogins logins;
    private final Environment environment;

    @TempDir
    private Path directory;

    LocalAppContextTests(
            @Autowired PostgreSQLContainer postgres, @Autowired TestLogins logins, @Autowired Environment environment) {
        this.postgres = postgres;
        this.logins = logins;
        this.environment = environment;
    }

    @Test
    void aToolGetsTheRealServicesAndNothingThatServesSendsOrMigrates() {
        LocalEnv env =
                LocalEnv.load(GateEnvFile.of(postgres, logins, environment).in(directory));

        try (ConfigurableApplicationContext context =
                GateEnvFile.quietly(LocalAppContext.application(env)).run()) {
            List<String> fromTestSources = new ArrayList<>();
            for (String name : context.getBeanDefinitionNames()) {
                Class<?> type = context.getType(name, false);
                CodeSource source =
                        type == null ? null : type.getProtectionDomain().getCodeSource();
                if (source != null && source.getLocation().getPath().contains("/test-classes/")) {
                    fromTestSources.add(name + " " + type.getName());
                }
            }
            assertThat(fromTestSources)
                    .as("beans from the test sources (fakes, test endpoints)")
                    .isEmpty();
            assertThat(context.getBean(WhatsAppMessages.class)).isNotNull();
            assertThat(context.getBeansOfType(senderType()).values())
                    .as("the sender that calls Meta, not the fake")
                    .singleElement()
                    .satisfies(sender ->
                            assertThat(sender.getClass().getSimpleName()).isEqualTo("CloudApiWhatsAppSender"));
            assertThat(context.containsBean("jobWorker")).as("the job server").isFalse();
            assertThat(context.containsBean("credentialStore"))
                    .as("the worker's credential store")
                    .isFalse();
            assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
            assertThat(context).as("no web server, so no open port").isNotInstanceOf(WebServerApplicationContext.class);

            boolean known = SystemScope.call(
                    UUID.randomUUID(),
                    () -> context.getBean(TenantDirectory.class).currentTenantExists());
            assertThat(known)
                    .as("it reads the database, through RLS, as app_user")
                    .isFalse();
        }
    }

    @Test
    void theFileOutranksEverySettingThatCouldPointElsewhere() {
        LocalEnv env =
                LocalEnv.load(GateEnvFile.of(postgres, logins, environment).in(directory));
        String elsewhere = "jdbc:postgresql://db.example.com:5432/postgres";
        List<String> names = List.of("DB_URL", "spring.datasource.url", "northstar.jobs.database.url");
        names.forEach(name -> System.setProperty(name, elsewhere));
        try (ConfigurableApplicationContext context =
                GateEnvFile.quietly(LocalAppContext.application(env)).run()) {
            for (String name : List.of(
                    "DB_URL",
                    "spring.datasource.url",
                    "northstar.jobs.database.url",
                    "northstar.credentials.database.url")) {
                assertThat(context.getEnvironment().getProperty(name))
                        .as(name)
                        .doesNotContain("example.com")
                        .contains("localhost");
            }
        } finally {
            names.forEach(System::clearProperty);
        }
    }

    @Test
    void aDatabaseElsewhereIsRefusedBeforeTheAppStarts() {
        LocalEnv env = LocalEnv.load(GateEnvFile.of(postgres, logins, environment)
                .set("DB_URL", "jdbc:postgresql://db.example.com:5432/postgres")
                .in(directory));

        assertThatThrownBy(() -> LocalAppContext.application(env))
                .isInstanceOf(LocalToolException.class)
                .hasMessageContaining("DB_URL")
                .hasMessageNotContaining("example.com");
    }

    private static Class<?> senderType() {
        try {
            return Class.forName("com.northstar.integrations.internal.WhatsAppSender");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
