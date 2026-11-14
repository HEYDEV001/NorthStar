package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The app refuses to start with a password in DB_URL, and never repeats the URL when it does (step 1.15). */
class DatabaseUrlCheckTests {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(DatabaseUrlCheck.class);

    @Test
    void aPasswordInTheDatabaseUrlStopsStartupWithoutRepeatingIt() {
        String password = "pw-" + UUID.randomUUID();
        for (String url : new String[] {
            "jdbc:postgresql://pooler.example.test:6543/postgres?sslmode=require&password=" + password,
            "jdbc:postgresql://pooler.example.test:6543/postgres?PASSWORD=" + password,
            "jdbc:postgresql://app_user:" + password + "@pooler.example.test:6543/postgres"
        }) {
            context.withPropertyValues("spring.datasource.url=" + url)
                    .run(started -> assertThat(started)
                            .getFailure()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("DB_URL must not carry a password; set DB_PASSWORD instead")
                            .hasMessageNotContaining(password)
                            .hasMessageNotContaining("pooler.example.test"));
        }
    }

    @Test
    void aUrlWithoutCredentialsStarts() {
        context.withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://pooler.example.test:6543/postgres?sslmode=require")
                .run(started -> assertThat(started).hasNotFailed());
        context.run(started -> assertThat(started).hasNotFailed());
    }
}
