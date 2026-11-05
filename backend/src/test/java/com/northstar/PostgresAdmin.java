package com.northstar;

import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test-only access as the container's postgres role, the admin an operator has on hosted Supabase. Fixtures, schema
 * probes and release-gate checks use this, never the app's datasource, so they keep working when the app connects as
 * app_user behind the tenant guard. postgres bypasses RLS.
 */
public final class PostgresAdmin implements AutoCloseable {

    private final PostgreSQLContainer postgres;
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactions;

    PostgresAdmin(PostgreSQLContainer postgres) {
        this.postgres = postgres;
    }

    /** Joins the transaction opened on this thread by {@link #beginRollbackOnly()}; otherwise commits each statement. */
    public JdbcClient jdbc() {
        connect();
        return jdbc;
    }

    public DataSource dataSource() {
        connect();
        return dataSource;
    }

    /** Runs the work in one transaction that is always rolled back, for probes that must leave nothing behind. */
    public void rolledBack(Consumer<JdbcClient> work) {
        rolledBack(jdbc -> {
            work.accept(jdbc);
            return null;
        });
    }

    public <T> T rolledBack(Function<JdbcClient, T> work) {
        connect();
        return transactions.execute(status -> {
            status.setRollbackOnly();
            return work.apply(jdbc);
        });
    }

    /**
     * Opens a transaction on this thread that {@link #jdbc()} joins until the returned handle rolls it back. For tests
     * that build a probe in {@code @BeforeEach} and roll it back in {@code @AfterEach}.
     */
    public RollbackOnly beginRollbackOnly() {
        connect();
        TransactionStatus status = transactionManager.getTransaction(TransactionDefinition.withDefaults());
        return () -> transactionManager.rollback(status);
    }

    /** Rolls back the transaction from {@link #beginRollbackOnly()}. */
    public interface RollbackOnly extends AutoCloseable {
        @Override
        void close();
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) dataSource.close();
    }

    // The container's port is known only once it has started, so connect on first use.
    private synchronized void connect() {
        if (dataSource != null) return;
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(postgres.getJdbcUrl());
        pool.setUsername(postgres.getUsername());
        pool.setPassword(postgres.getPassword());
        pool.setMaximumPoolSize(4);
        pool.setPoolName("postgres-admin");
        dataSource = pool;
        jdbc = JdbcClient.create(pool);
        transactionManager = new DataSourceTransactionManager(pool);
        transactions = new TransactionTemplate(transactionManager);
    }
}
