package com.northstar.devtools;

import com.northstar.TestcontainersConfiguration.TestLogins;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A .env.local for the gate tools' tests, pointing at the tests' own throwaway database. Never the developer's file.
 */
final class GateEnvFile {

    private final Map<String, String> settings = new LinkedHashMap<>();

    private GateEnvFile() {}

    static GateEnvFile of(PostgreSQLContainer postgres, TestLogins logins, Environment environment) {
        GateEnvFile file = new GateEnvFile();
        file.set("DB_URL", withoutOptions(postgres.getJdbcUrl()));
        file.set("DB_USER", "app_user");
        file.set("DB_PASSWORD", logins.appUserPassword());
        file.set("JOBRUNR_DB_USER", "jobrunr_user");
        file.set("JOBRUNR_DB_PASSWORD", logins.jobRunrPassword());
        file.set("SECRETS_DB_USER", "secrets_user");
        file.set("SECRETS_DB_PASSWORD", logins.secretsUserPassword());
        file.set("SUPABASE_URL", "http://127.0.0.1:54321");
        file.set("NORTHSTAR_CREDENTIAL_KEYS", environment.getRequiredProperty("northstar.credentials.keys"));
        file.set(
                "NORTHSTAR_CREDENTIAL_ACTIVE_KEY", environment.getRequiredProperty("northstar.credentials.active-key"));
        return file;
    }

    GateEnvFile set(String name, String value) {
        settings.put(name, value);
        return this;
    }

    /** Writes the file into the directory and returns its path. */
    Path in(Path directory) {
        StringBuilder text = new StringBuilder();
        settings.forEach(
                (name, value) -> text.append(name).append('=').append(value).append('\n'));
        try {
            return Files.writeString(directory.resolve(".env.local"), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The app as a tool starts it, but without its logging set-up, so the tests' own logging is left alone. */
    static SpringApplication quietly(SpringApplication application) {
        application.setListeners(application.getListeners().stream()
                .filter(listener -> !(listener instanceof LoggingApplicationListener))
                .toList());
        return application;
    }

    /** Testcontainers adds options to its URL (loggerLevel), which the guard refuses. */
    private static String withoutOptions(String url) {
        int options = url.indexOf('?');
        return options < 0 ? url : url.substring(0, options);
    }
}
