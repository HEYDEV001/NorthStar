package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The gate tools work with the local database only (step 2.11): every way a setting could point elsewhere is refused,
 * naming the setting and never its value.
 */
class LocalOnlyTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://127.0.0.1:54322/postgres",
                "jdbc:postgresql://localhost:54322/postgres",
                "jdbc:postgresql://[::1]:54322/postgres",
                "jdbc:postgresql://localhost/postgres",
                "jdbc:postgresql://127.0.0.1:54322/postgres?",
                "jdbc:postgresql://127.0.0.1:54322/postgres?sslmode",
                "jdbc:postgresql://127.0.0.1:54322/postgres?sslmode=disable&prepareThreshold=0&ApplicationName=gate"
            })
    void aDatabaseOnThisMachineIsAccepted(String url) {
        assertThatCode(() -> LocalOnly.database("DB_URL", url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                // Elsewhere, or a name that only looks like this machine.
                "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:6543/postgres",
                "jdbc:postgresql://db.example.com/postgres",
                "jdbc:postgresql://10.0.0.5:5432/postgres",
                "jdbc:postgresql://127.0.0.1.example.com:5432/postgres",
                "jdbc:postgresql://localhost.example.com/postgres",
                "jdbc:postgresql://example.com#127.0.0.1/postgres",
                "jdbc:postgresql://127.0.0.2/postgres",
                "jdbc:postgresql://0.0.0.0:5432/postgres",
                "jdbc:postgresql://[::2]:5432/postgres",
                // Several hosts: the driver tries them in turn.
                "jdbc:postgresql://127.0.0.1:54322,db.example.com:5432/postgres",
                "jdbc:postgresql://db.example.com:5432,127.0.0.1:54322/postgres",
                // A login in the address.
                "jdbc:postgresql://app_user:secret@127.0.0.1:54322/postgres",
                "jdbc:postgresql://127.0.0.1@db.example.com/postgres",
                // Options that can change where the driver connects, or make it read something to find out.
                "jdbc:postgresql://127.0.0.1:54322/postgres?PGHOST=db.example.com",
                "jdbc:postgresql://127.0.0.1:54322/postgres?sslmode=require&PGPORT=5432",
                "jdbc:postgresql://127.0.0.1:54322/postgres?socketFactory=com.example.Evil",
                "jdbc:postgresql://127.0.0.1:54322/postgres?service=staging",
                "jdbc:postgresql://127.0.0.1:54322/postgres?password=secret",
                "jdbc:postgresql://127.0.0.1:54322/postgres?%50GHOST=db.example.com",
                "jdbc:postgresql://127.0.0.1:54322/postgres?&PGHOST=db.example.com",
                // Not the driver's host form, or not Postgres at all.
                "jdbc:postgresql:postgres",
                "jdbc:postgresql:///postgres",
                "postgresql://127.0.0.1:54322/postgres",
                "jdbc:mysql://127.0.0.1/postgres",
                " jdbc:postgresql://127.0.0.1:54322/postgres",
                "jdbc:postgresql://127.0.0.1:54322/post/gres",
                "jdbc:postgresql://127.0.0.1:54322/"
            })
    void anythingElseIsRefusedNamingTheSettingAndNotItsValue(String url) {
        assertThatThrownBy(() -> LocalOnly.database("DB_URL", url))
                .isInstanceOfSatisfying(LocalToolException.class, e -> {
                    assertThat(e.code()).isEqualTo("database_not_local");
                    assertThat(e.getMessage()).contains("DB_URL", "Nothing was done");
                    assertThat(e.getMessage()).doesNotContain("example.com", "secret", "Evil", "10.0.0.5");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:54321", "http://localhost:54321/", "https://[::1]:54321"})
    void aServiceOnThisMachineIsAccepted(String url) {
        assertThatCode(() -> LocalOnly.service("SUPABASE_URL", url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "https://abcdef.supabase.co",
                "http://127.0.0.1.example.com:54321",
                "http://user:secret@127.0.0.1:54321",
                "http://127.0.0.1:54321?next=https://example.com",
                "http://127.0.0.1:54321#x",
                "ftp://127.0.0.1",
                "127.0.0.1:54321",
                "http:// 127.0.0.1"
            })
    void aServiceElsewhereIsRefused(String url) {
        assertThatThrownBy(() -> LocalOnly.service("SUPABASE_URL", url))
                .isInstanceOfSatisfying(LocalToolException.class, e -> {
                    assertThat(e.code()).isEqualTo("service_not_local");
                    assertThat(e.getMessage()).contains("SUPABASE_URL").doesNotContain("secret", "example.com");
                });
    }

    @Test
    void theFileIsReadAsTheAppReadsItAndShowsNoValues(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(
                directory.resolve(".env.local"),
                "# comment\nDB_URL = jdbc:postgresql://127.0.0.1:54322/postgres  \nEMPTY=\nSECRET=hush=hush\n");

        LocalEnv env = LocalEnv.load(file);

        assertThat(env.get("DB_URL")).contains("jdbc:postgresql://127.0.0.1:54322/postgres");
        assertThat(env.get("SECRET")).as("only the first = separates").contains("hush=hush");
        assertThat(env.get("EMPTY")).isEmpty();
        assertThat(env.toString()).isEqualTo("LocalEnv[DB_URL, SECRET]").doesNotContain("hush");
        assertThatThrownBy(() -> env.require("EMPTY")).isInstanceOfSatisfying(LocalToolException.class, e -> {
            assertThat(e.code()).isEqualTo("setting_missing");
            assertThat(e.getMessage()).contains("EMPTY");
        });
        assertThatThrownBy(() -> LocalEnv.load(directory.resolve("missing")))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("env_file_unreadable"));
    }
}
