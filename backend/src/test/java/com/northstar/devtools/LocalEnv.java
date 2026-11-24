package com.northstar.devtools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;

/**
 * The developer's .env.local, the only place the gate tools (step 2.11) take database, key and secret settings from.
 * The file is read as the app reads it (a .properties file, one directory above backend/), and what it says is handed
 * to the app's context above every other source, so a DB_URL exported in a shell cannot send a tool to another
 * database.
 *
 * <p>{@link #requireLocal()} refuses a database or Supabase that is not on this machine ({@link LocalOnly}). Nothing
 * here logs, and {@link #toString()} lists setting names only.
 */
public final class LocalEnv {

    /** Relative to backend/, the directory the app and the tools run in. */
    public static final Path FILE = Path.of("..", ".env.local");

    static final String DB_URL = "DB_URL";
    static final String SUPABASE_URL = "SUPABASE_URL";
    static final String APP_SECRET = "NORTHSTAR_WHATSAPP_APP_SECRET";

    private final Map<String, String> values;

    private LocalEnv(Map<String, String> values) {
        this.values = values;
    }

    public static LocalEnv load(Path file) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new LocalToolException(
                    "env_file_unreadable",
                    "No readable .env.local in the repo root. Copy .env.example to .env.local and fill it in.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name).strip();
            if (!value.isEmpty()) values.put(name, value);
        }
        return new LocalEnv(values);
    }

    Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    /** The setting's value, or a refusal naming the setting. */
    String require(String name) {
        return get(name)
                .orElseThrow(() -> new LocalToolException(
                        "setting_missing", name + " is empty or missing in .env.local. Nothing was done."));
    }

    /** Refuses a database or Supabase that is not on this machine, before anything else is read or asked for. */
    void requireLocal() {
        LocalOnly.database(DB_URL, require(DB_URL));
        LocalOnly.service(SUPABASE_URL, require(SUPABASE_URL));
    }

    /**
     * What the app's context gets, above every other source. The database and key settings, under both the names the
     * app's own files use and the names those resolve to, so no other source can override either. The worker, the web
     * server's webhook secrets and Flyway are off or throwaway: a tool sends nothing, serves nothing and never
     * migrates.
     */
    Map<String, Object> appProperties() {
        Map<String, Object> app = new LinkedHashMap<>();
        String url = require(DB_URL);
        put(app, "spring.datasource.url", url);
        put(app, "spring.datasource.username", require("DB_USER"));
        put(app, "spring.datasource.password", require("DB_PASSWORD"));
        put(app, "northstar.jobs.database.url", url);
        put(app, "northstar.jobs.database.username", require("JOBRUNR_DB_USER"));
        put(app, "northstar.jobs.database.password", require("JOBRUNR_DB_PASSWORD"));
        put(app, "northstar.credentials.database.url", url);
        get("SECRETS_DB_USER").ifPresent(user -> put(app, "northstar.credentials.database.username", user));
        get("SECRETS_DB_PASSWORD").ifPresent(secret -> put(app, "northstar.credentials.database.password", secret));
        get("NORTHSTAR_CREDENTIAL_KEYS").ifPresent(keys -> put(app, "northstar.credentials.keys", keys));
        get("NORTHSTAR_CREDENTIAL_ACTIVE_KEY").ifPresent(key -> put(app, "northstar.credentials.active-key", key));
        put(app, "SUPABASE_URL", require(SUPABASE_URL));
        put(app, "DB_URL", url);

        put(app, "northstar.worker.enabled", "false");
        put(app, "northstar.webhooks.whatsapp.app-secret", throwaway());
        put(app, "northstar.webhooks.whatsapp.verify-token", throwaway());
        put(app, "northstar.local-env.import", "");
        put(app, "spring.flyway.enabled", "false");
        return app;
    }

    private static void put(Map<String, Object> app, String name, String value) {
        app.put(name, value);
    }

    /** A random value that is well-formed but means nothing: the tools never need the real secrets. */
    private static String throwaway() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    @Override
    public String toString() {
        return "LocalEnv" + new TreeSet<>(values.keySet());
    }
}
