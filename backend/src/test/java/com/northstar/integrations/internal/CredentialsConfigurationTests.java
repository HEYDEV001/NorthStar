package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Where the credential store exists (step 2.1b): never in an API container, and on the worker only with every setting
 * present and well formed. Each refusal happens at startup, before any connection, names the setting and never its
 * value.
 */
class CredentialsConfigurationTests {

    private static final String KEY = CredentialKeyringTests.randomKey(32);
    private static final String PASSWORD = "secrets-password-" + KEY.substring(0, 10);

    /** An API container: the worker is off, as on every api component. */
    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    class OnTheApi {

        @Autowired
        ApplicationContext context;

        @Test
        void thereIsNoCredentialStoreKeyringOrCipher() {
            assertThat(context.getEnvironment().getProperty("northstar.worker.enabled"))
                    .isEqualTo("false");
            assertThat(context.getBeanNamesForType(CredentialStore.class)).isEmpty();
            assertThat(context.getBeanNamesForType(CredentialKeyring.class)).isEmpty();
            assertThat(context.getBeanNamesForType(CredentialCipher.class)).isEmpty();
        }
    }

    @Nested
    class OnTheWorker {

        private final ApplicationContextRunner worker = new ApplicationContextRunner()
                .withUserConfiguration(CredentialsConfiguration.class)
                .withPropertyValues(
                        "northstar.worker.enabled=true",
                        "northstar.credentials.keys=k1:" + KEY,
                        "northstar.credentials.active-key=k1",
                        // Nothing listens here: every case below must fail before connecting.
                        "northstar.credentials.database.url=jdbc:postgresql://localhost:1/postgres",
                        "northstar.credentials.database.username=secrets_user",
                        "northstar.credentials.database.password=" + PASSWORD,
                        "northstar.credentials.database.pool-size=2");

        @Test
        void missingOrMalformedKeysStopTheWorker() {
            assertRefused("northstar.credentials.keys=", "NORTHSTAR_CREDENTIAL_KEYS is required when the worker is on");
            assertRefused(
                    "northstar.credentials.active-key=",
                    "NORTHSTAR_CREDENTIAL_ACTIVE_KEY is required when the worker is on");
            assertRefused(
                    "northstar.credentials.keys=k1:" + KEY.substring(0, 20),
                    "NORTHSTAR_CREDENTIAL_KEYS key k1 must be exactly 32 bytes");
            assertRefused(
                    "northstar.credentials.active-key=k2",
                    "NORTHSTAR_CREDENTIAL_ACTIVE_KEY must name a key in NORTHSTAR_CREDENTIAL_KEYS");
        }

        @Test
        void aDatabaseUrlCarryingAPasswordIsRefused() {
            for (String url : List.of(
                    "jdbc:postgresql://localhost:1/postgres?password=" + PASSWORD,
                    "jdbc:postgresql://secrets_user:" + PASSWORD + "@localhost:1/postgres")) {
                assertRefused(
                        "northstar.credentials.database.url=" + url,
                        "The credentials database URL must not carry a password; set SECRETS_DB_PASSWORD");
            }
        }

        @Test
        void missingLoginSettingsStopTheWorker() {
            assertRefused("northstar.credentials.database.url=", "DB_URL is required when the worker is on");
            assertRefused(
                    "northstar.credentials.database.username=", "SECRETS_DB_USER is required when the worker is on");
            assertRefused(
                    "northstar.credentials.database.password=",
                    "SECRETS_DB_PASSWORD is required when the worker is on");
        }

        @Test
        void withTheWorkerOffNothingIsBuiltOrChecked() {
            worker.withPropertyValues("northstar.worker.enabled=false", "northstar.credentials.keys=")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(CredentialStore.class);
                    });
        }

        private void assertRefused(String override, String message) {
            worker.withPropertyValues(override).run(context -> {
                assertThat(context).hasFailed();
                List<String> messages = new ArrayList<>();
                for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                    messages.add(String.valueOf(cause.getMessage()));
                }
                assertThat(messages).as(override).contains(message);
                for (String each : messages) {
                    assertThat(each).doesNotContain(KEY).doesNotContain(PASSWORD);
                }
            });
        }
    }
}
