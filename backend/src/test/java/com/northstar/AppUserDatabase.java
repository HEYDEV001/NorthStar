package com.northstar;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Test access through the real app_user login, so tests see exactly what RLS and column privileges allow. Each call
 * runs in its own transaction with app.tenant_id, app.user_id and app.role set locally, as every app transaction does
 * (data-model.md section 12).
 */
public final class AppUserDatabase {

    private final PostgreSQLContainer postgres;
    private final String password;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;

    AppUserDatabase(PostgreSQLContainer postgres, String password) {
        this.postgres = postgres;
        this.password = password;
    }

    public <T> T as(UUID tenantId, UUID userId, String role, Function<JdbcClient, T> work) {
        connect();
        return transactions.execute(status -> {
            jdbc.sql("""
                            select set_config('app.tenant_id', :tenantId, true),
                                   set_config('app.user_id', :userId, true),
                                   set_config('app.role', :role, true)""")
                    .param("tenantId", tenantId.toString())
                    .param("userId", userId.toString())
                    .param("role", role)
                    .query()
                    .listOfRows();
            return work.apply(jdbc);
        });
    }

    public void run(UUID tenantId, UUID userId, String role, Consumer<JdbcClient> work) {
        as(tenantId, userId, role, jdbc -> {
            work.accept(jdbc);
            return null;
        });
    }

    // The container's port is known only once it has started, so connect on first use.
    private synchronized void connect() {
        if (jdbc != null) return;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), "app_user", password);
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
