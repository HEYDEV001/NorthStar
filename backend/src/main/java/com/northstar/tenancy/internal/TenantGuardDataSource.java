package com.northstar.tenancy.internal;

import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.TenantContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps the app's pool so no statement reaches Postgres without a tenant context. Each connection it hands out refuses
 * createStatement, prepareStatement and prepareCall with {@link MissingTenantContextException} until
 * {@link TenantAwareJpaTransactionManager} begins a tenant transaction on it; commit, rollback and close make it refuse
 * again. Without this, a query outside a tenant transaction would run with no app.tenant_id and RLS would silently
 * return nothing.
 *
 * <p>One exception: Hibernate's schema validation reads sequence metadata with a statement while the app starts. Until
 * {@link #closeStartupWindow()} runs, statements are allowed on the thread that created this datasource only. The
 * window closes after every singleton exists and before the web server accepts requests.
 *
 * <p>This guards only the app_user datasource. The app_admin datasource (Phase 8) is a separate bean with its own rules.
 */
public final class TenantGuardDataSource extends DelegatingDataSource {

    private static final String SET_CONTEXT = "select set_config('app.tenant_id', ?, true),"
            + " set_config('app.user_id', ?, true), set_config('app.role', ?, true)";

    private final Thread startupThread = Thread.currentThread();
    private volatile boolean startupWindowOpen = true;

    public TenantGuardDataSource(DataSource target) {
        super(target);
    }

    public void closeStartupWindow() {
        startupWindowOpen = false;
    }

    public boolean isStartupWindowOpen() {
        return startupWindowOpen;
    }

    /** True only on the thread that created this datasource, and only until the startup window closes. */
    boolean isStartupThreadInWindow() {
        return startupWindowOpen && Thread.currentThread() == startupThread;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return guard(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return guard(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection guard(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                TenantGuardDataSource.class.getClassLoader(),
                new Class<?>[] {TenantGuardedConnection.class},
                new Guard(target));
    }

    private final class Guard implements InvocationHandler {

        private final Connection target;
        private boolean tenantTransaction;

        Guard(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "beginTenantTransaction" -> {
                    begin((TenantContext) args[0]);
                    return null;
                }
                case "createStatement", "prepareStatement", "prepareCall" -> {
                    if (!tenantTransaction && !isStartupThreadInWindow()) {
                        throw new MissingTenantContextException(
                                "Statement refused: no tenant transaction on this connection");
                    }
                }
                case "commit", "close" -> tenantTransaction = false;
                case "rollback" -> {
                    if (args == null) tenantTransaction = false; // rollback to a savepoint stays in the transaction
                }
                case "setAutoCommit" -> {
                    if ((Boolean) args[0]) tenantTransaction = false;
                }
                case "unwrap" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                }
                case "isWrapperFor" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) return true;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "TenantGuardedConnection[" + target + "]";
                }
                default -> {}
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        private void begin(TenantContext context) throws SQLException {
            if (target.getAutoCommit()) {
                throw new IllegalStateException("A tenant context can only be set inside a transaction");
            }
            try (PreparedStatement statement = target.prepareStatement(SET_CONTEXT)) {
                statement.setString(1, context.tenantId().toString());
                statement.setString(2, context.userId().toString());
                statement.setString(3, context.role().databaseValue());
                statement.executeQuery().close();
            }
            tenantTransaction = true;
        }
    }
}
