package com.northstar.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** JobRunr's pool refuses a password in its URL before connecting, and never repeats the URL (step 1.15). */
class JobRunrDatabaseUrlTests {

    @Test
    void aPasswordInJobRunrsUrlIsRefusedBeforeAnyConnection() {
        String password = "pw-" + UUID.randomUUID();
        String url = "jdbc:postgresql://pooler.example.test:6543/postgres?password=" + password;

        assertThatThrownBy(() -> new JobsConfiguration().jobRunrStorageProvider(url, "jobrunr_user", "unused", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The JobRunr database URL must not carry a password; set JOBRUNR_DB_PASSWORD")
                .hasMessageNotContaining(password);
    }

    @Test
    void onlyAPasswordOrUserInfoCountsAsCredentials() {
        assertThat(JobsConfiguration.carriesPassword("jdbc:postgresql://h:6543/postgres?sslmode=require"))
                .isFalse();
        assertThat(JobsConfiguration.carriesPassword("jdbc:postgresql://h:6543/postgres?user=jobrunr_user.ref"))
                .isFalse();
        assertThat(JobsConfiguration.carriesPassword("jdbc:postgresql://h:6543/postgres?sslpassword=x"))
                .isTrue();
        assertThat(JobsConfiguration.carriesPassword("jdbc:postgresql://u:p@h:6543/postgres"))
                .isTrue();
    }
}
