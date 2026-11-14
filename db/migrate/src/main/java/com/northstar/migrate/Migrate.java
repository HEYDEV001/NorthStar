package com.northstar.migrate;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies db/migrations to a hosted database as migrator: the deploy's pre-deploy job (step 1.15). A failure exits
 * non-zero, which fails the deployment before the new app version starts. Its own small build: it imports no app code,
 * and the app itself never migrates (Flyway is not on the app's runtime classpath).
 *
 * <p>Configuration comes only from FLYWAY_URL, FLYWAY_USER and FLYWAY_PASSWORD. The Flyway settings match the Maven
 * plugin's in backend/pom.xml, which MigrationRunnerTests checks. Output is migration versions, names and status codes
 * only: never the password, a URL carrying credentials, or the SQL a migration runs (see {@link MigrationLog}).
 */
public final class Migrate {

    /** Where the image puts db/migrations; the runner reads nothing else. */
    static final Path MIGRATIONS = Path.of("/opt/northstar/migrations");

    static final List<String> SETTINGS = List.of("FLYWAY_URL", "FLYWAY_USER", "FLYWAY_PASSWORD");

    /** States that need no attention after a failed validation; every other row is printed. */
    private static final Set<MigrationState> SETTLED =
            EnumSet.of(MigrationState.SUCCESS, MigrationState.PENDING, MigrationState.BASELINE);

    private Migrate() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv(), MIGRATIONS));
    }

    /** The exit code: 0 migrated or nothing to do, 1 failed, 2 refused to start. */
    public static int run(Map<String, String> env, Path migrations) {
        for (String name : SETTINGS) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                print("migrate_refused reason=missing_setting name=" + name);
                return 2;
            }
        }
        if (carriesCredentials(env.get("FLYWAY_URL"))) {
            print("migrate_refused reason=credentials_in_url name=FLYWAY_URL");
            return 2;
        }

        MigrationTracker tracker = new MigrationTracker();
        Flyway flyway = configure(migrations)
                .dataSource(env.get("FLYWAY_URL"), env.get("FLYWAY_USER"), env.get("FLYWAY_PASSWORD"))
                .callbacks(tracker)
                .load();
        try {
            MigrateResult result = flyway.migrate();
            String version =
                    result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion;
            print("migrate_succeeded applied=" + result.migrationsExecuted + " schema_version=" + version);
            return 0;
        } catch (FlywayValidateException e) {
            print("migrate_failed reason=validation error_code=" + e.getErrorCode());
            printUnsettledMigrations(flyway);
            return 1;
        } catch (RuntimeException e) {
            String migration = tracker.current != null ? " " + tracker.current : "";
            print("migrate_failed reason=" + e.getClass().getSimpleName() + " error_code=" + errorCode(e)
                    + " sql_state=" + sqlState(e) + migration);
            return 1;
        }
    }

    /** The settings shared with the Maven plugin (backend/pom.xml); the connection is added by {@link #run}. */
    public static FluentConfiguration configure(Path migrations) {
        return Flyway.configure(Migrate.class.getClassLoader())
                .locations("filesystem:" + migrations.toAbsolutePath())
                .schemas("northstar")
                .createSchemas(true)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .loggers(MigrationLog.class.getName());
    }

    /** A password belongs in FLYWAY_PASSWORD, never in the URL, where logs and error messages may show it. */
    static boolean carriesCredentials(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int authorityStart = lower.indexOf("//");
        int authorityEnd = authorityStart < 0 ? -1 : lower.indexOf('/', authorityStart + 2);
        String authority = authorityStart < 0
                ? ""
                : lower.substring(authorityStart + 2, authorityEnd < 0 ? lower.length() : authorityEnd);
        return lower.contains("password") || authority.contains("@");
    }

    /** After a failed validation, the migrations that caused it: version, state and whether the checksum matches. */
    private static void printUnsettledMigrations(Flyway flyway) {
        try {
            for (MigrationInfo info : flyway.info().all()) {
                if (!SETTLED.contains(info.getState()) || !info.isChecksumMatching()) {
                    print("migration " + describe(info) + " state=" + info.getState() + " checksum_matches="
                            + info.isChecksumMatching());
                }
            }
        } catch (RuntimeException e) {
            print("migrate_info_failed reason=" + e.getClass().getSimpleName() + " sql_state=" + sqlState(e));
        }
    }

    private static String describe(MigrationInfo info) {
        return "version=" + info.getVersion() + " description=\"" + info.getDescription() + "\"";
    }

    private static String errorCode(RuntimeException e) {
        return e instanceof FlywayException flyway ? String.valueOf(flyway.getErrorCode()) : "none";
    }

    /** The first SQL state in the cause chain (e.g. 28P01 for a wrong password), never the message. */
    private static String sqlState(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return "none";
    }

    private static void print(String line) {
        System.out.println(line);
        System.out.flush();
    }

    /** Remembers which migration is running, so a failure can name its version without quoting Flyway's message. */
    private static final class MigrationTracker implements Callback {

        private volatile String current;

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            current = event == Event.BEFORE_EACH_MIGRATE ? describe(context.getMigrationInfo()) : null;
        }

        @Override
        public String getCallbackName() {
            return "northstar-migration-tracker";
        }
    }
}
