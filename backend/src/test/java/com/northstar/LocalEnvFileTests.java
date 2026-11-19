package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Tests never read the developer's .env.local, so a test behaves the same on a laptop as in CI. The app still imports
 * it (spring-boot:run); the test classpath turns that import off (src/test/resources/config/application.properties).
 *
 * <p>CI has no .env.local, so checking the property sources alone would pass there whatever the setting. The second
 * test therefore proves the mechanism with a file it writes itself: the app's import follows the setting, and with the
 * tests' setting nothing is imported.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LocalEnvFileTests {

    private static final Path APP_PROPERTIES = Path.of("src/main/resources/application.properties");

    @Autowired
    ConfigurableEnvironment environment;

    @Test
    void aTestEnvironmentImportsNoLocalEnvFile() {
        List<String> sources = StreamSupport.stream(
                        environment.getPropertySources().spliterator(), false)
                .map(PropertySource::getName)
                .toList();

        assertThat(environment.getProperty("northstar.local-env.import")).isEmpty();
        assertThat(sources).as("property sources of the test context").noneMatch(name -> name.contains(".env"));
    }

    @Test
    void theImportFollowsTheSettingAndTheTestSettingImportsNothing(@TempDir Path dir) throws IOException {
        Path canary = dir.resolve(".env.local");
        Files.writeString(canary, "NORTHSTAR_LOCAL_ENV_CANARY=loaded\n");

        try (ConfigurableApplicationContext asTests = start()) {
            assertThat(asTests.getEnvironment().getProperty("NORTHSTAR_LOCAL_ENV_CANARY"))
                    .as("with the tests' setting")
                    .isNull();
        }
        try (ConfigurableApplicationContext pointedAtTheFile =
                start("--northstar.local-env.import=optional:file:" + canary + "[.properties]")) {
            assertThat(pointedAtTheFile.getEnvironment().getProperty("NORTHSTAR_LOCAL_ENV_CANARY"))
                    .as("the same import, pointed at a file")
                    .isEqualTo("loaded");
        }
    }

    @Test
    void theAppAndTestRunStillReadTheLocalEnvFile() throws IOException {
        assertThat(Files.readString(APP_PROPERTIES))
                .contains(
                        "spring.config.import=${northstar.local-env.import:optional:file:../.env.local[.properties]}");
        assertThat(TestNorthstarApplication.READ_LOCAL_ENV)
                .isEqualTo("--northstar.local-env.import=optional:file:../.env.local[.properties]");
    }

    /**
     * A bare context on the same classpath and config files as the tests, with no beans and no web server. It leaves
     * logging alone, so the other tests' log setup and output capture are untouched.
     */
    private static ConfigurableApplicationContext start(String... args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(Bare.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false);
        SpringApplication application = builder.application();
        application.setListeners(application.getListeners().stream()
                .filter(listener -> !(listener instanceof LoggingApplicationListener))
                .toList());
        return builder.run(args);
    }

    @Configuration(proxyBeanMethods = false)
    static class Bare {}
}
