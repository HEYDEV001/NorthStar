package com.northstar.tenancy.internal;

import java.sql.SQLException;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Reads what the database said from a failure's cause chain: the SQLSTATE and, for constraint violations, the
 * constraint's name. Services use these to turn a specific database outcome into a specific API error. Never the
 * message text.
 */
final class DatabaseErrors {

    /** Raised by northstar.keep_an_active_admin(): the change would leave the business without an active admin. */
    static final String LAST_ADMIN = "NSA01";

    /** Deadlock, lock not available (lock or statement timeout), serialization failure: safe to retry. */
    static final Set<String> RETRYABLE = Set.of("40P01", "55P03", "40001");

    static final String ONE_ACTIVE_MEMBERSHIP = "memberships_one_active_per_user";

    private DatabaseErrors() {}

    static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    static String constraintName(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) return violation.getConstraintName();
        }
        return null;
    }
}
