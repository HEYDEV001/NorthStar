package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

/** The retryable codes recorded when processing a webhook event fails (step 2.4b, decided 20 Nov 2026). */
class WebhookFailuresTests {

    @Test
    void connectionFailuresAreDatabaseUnavailable() {
        assertThat(code(sql("08006"))).isEqualTo("database_unavailable");
        assertThat(code(sql("08001"))).isEqualTo("database_unavailable");
        assertThat(code(new CannotCreateTransactionException("no connection"))).isEqualTo("database_unavailable");
        assertThat(code(new DataAccessResourceFailureException(
                        "x", new SQLTransientConnectionException("pool timed out"))))
                .isEqualTo("database_unavailable");
    }

    @Test
    void lockAndUniqueConflictsAreDatabaseConflict() {
        for (String state : new String[] {"40001", "40P01", "55P03", "23505"}) {
            assertThat(code(sql(state))).as(state).isEqualTo("database_conflict");
        }
        assertThat(code(new OptimisticLockingFailureException("version moved"))).isEqualTo("database_conflict");
        assertThat(code(new CannotAcquireLockException("busy"))).isEqualTo("database_conflict");
    }

    @Test
    void aCancelledStatementIsDatabaseTimeout() {
        assertThat(code(sql("57014"))).isEqualTo("database_timeout");
        assertThat(code(new QueryTimeoutException("slow"))).isEqualTo("database_timeout");
    }

    @Test
    void anythingElseIsProcessingFailed() {
        assertThat(code(new IllegalStateException("bug"))).isEqualTo("processing_failed");
        assertThat(code(sql("23503"))).as("a foreign key violation").isEqualTo("processing_failed");
        assertThat(code(new DataIntegrityViolationException("check"))).isEqualTo("processing_failed");
    }

    @Test
    void aCauseChainThatLoopsStillEnds() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(code(first)).isEqualTo("processing_failed");
    }

    private static RuntimeException sql(String state) {
        return new UncategorizedSQLException("task", "select 1", new SQLException("failed", state));
    }

    private static String code(RuntimeException failure) {
        return WebhookFailures.retryableCode(failure);
    }
}
