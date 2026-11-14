package com.northstar.migrate;

import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;

/**
 * Flyway's log for the migration runner, set through Flyway's loggers setting so that only the runner's configuration
 * uses it. Debug lines, where Flyway prints the SQL it runs, are dropped, and an error never prints its exception,
 * whose message can quote a statement. Everything goes to standard output, which App Platform keeps as the job's log.
 */
public final class MigrationLog implements LogCreator {

    private static final Log LOG = new Log() {
        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(String message) {}

        @Override
        public void info(String message) {
            print(message);
        }

        @Override
        public void warn(String message) {
            print("WARN " + message);
        }

        @Override
        public void error(String message) {
            print("ERROR " + message);
        }

        @Override
        public void error(String message, Exception e) {
            print("ERROR " + message + " (" + e.getClass().getSimpleName() + ")");
        }

        @Override
        public void notice(String message) {
            print(message);
        }
    };

    @Override
    public Log createLogger(Class<?> source) {
        return LOG;
    }

    private static void print(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
