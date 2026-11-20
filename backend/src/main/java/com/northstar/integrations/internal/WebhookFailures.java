package com.northstar.integrations.internal;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The fixed code recorded when processing a webhook event fails and will be retried (step 2.4b, decided 20 Nov 2026).
 * The event's {@code processed_at} stays null and the webhook sweep retries it (integration-specs.md section 1). Read
 * from the failure's SQLSTATE and class only, never its message, which may quote data.
 *
 * <ul>
 *   <li>{@value #DATABASE_UNAVAILABLE}: SQLSTATE class 08, or no connection;
 *   <li>{@value #DATABASE_CONFLICT}: serialization failure, deadlock, lock not available or a unique violation (a re-run
 *       sees the row another transaction committed), or an optimistic lock failure;
 *   <li>{@value #DATABASE_TIMEOUT}: a cancelled statement;
 *   <li>{@value #PROCESSING_FAILED}: anything else.
 * </ul>
 *
 * The permanent codes, recorded with processed_at, are {@link WhatsAppInboundMessage#PAYLOAD_INVALID} and
 * {@link WhatsAppInboundMessage#CONTACT_PHONE_INVALID}.
 */
final class WebhookFailures {

    static final String DATABASE_UNAVAILABLE = "database_unavailable";
    static final String DATABASE_CONFLICT = "database_conflict";
    static final String DATABASE_TIMEOUT = "database_timeout";
    static final String PROCESSING_FAILED = "processing_failed";

    private static final Set<String> CONFLICT_STATES = Set.of("40001", "40P01", "55P03", "23505");
    private static final String QUERY_CANCELED = "57014";

    /** How deep the cause chain is followed, in case one loops. */
    private static final int MAX_CAUSES = 32;

    private WebhookFailures() {}

    static String retryableCode(Throwable failure) {
        String state = sqlState(failure);
        if (state != null) {
            if (state.startsWith("08")) return DATABASE_UNAVAILABLE;
            if (CONFLICT_STATES.contains(state)) return DATABASE_CONFLICT;
            if (QUERY_CANCELED.equals(state)) return DATABASE_TIMEOUT;
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++, cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException
                    || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof SQLTransientConnectionException) {
                return DATABASE_UNAVAILABLE;
            }
            if (cause instanceof ConcurrencyFailureException) return DATABASE_CONFLICT;
            if (cause instanceof QueryTimeoutException) return DATABASE_TIMEOUT;
        }
        return PROCESSING_FAILED;
    }

    private static String sqlState(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }
}
