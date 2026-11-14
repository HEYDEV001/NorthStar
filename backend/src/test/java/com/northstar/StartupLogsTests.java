package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Nothing the app prints while starting carries a secret (step 1.15): a worker container, which holds every secret the
 * app uses, starts here in a fresh context while all output is captured. The extra property keeps this context out of
 * the cache, so its whole startup is in the capture.
 */
@SpringBootTest(properties = {"northstar.worker.enabled=true", "northstar.test.startup-logs=true"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StartupLogsTests {

    private final Environment environment;
    private final TestcontainersConfiguration.TestLogins logins;

    StartupLogsTests(@Autowired Environment environment, @Autowired TestcontainersConfiguration.TestLogins logins) {
        this.environment = environment;
        this.logins = logins;
    }

    @Test
    void startupPrintsNoSecret(CapturedOutput output) {
        List<String> secrets = List.of(
                environment.getRequiredProperty("northstar.email.resend.api-key"),
                environment.getRequiredProperty("northstar.invites.token-key"),
                environment.getRequiredProperty("northstar.jobs.database.password"),
                logins.appUserPassword(),
                logins.migratorPassword());

        assertThat(output).as("the capture covers the startup").contains("Started ");
        for (String secret : secrets) {
            assertThat(secret).hasSizeGreaterThan(16);
            assertThat(output.getAll()).doesNotContain(secret);
        }
        assertThat(output.getAll().toLowerCase()).doesNotContain("password=");
    }
}
