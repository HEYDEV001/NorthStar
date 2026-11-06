package com.northstar.tenancy.internal;

import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.TenantContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>No route may lead back to the unguarded connection. unwrap and isWrapperFor answer for the guarded object itself
 * and refuse anything underneath outside a tenant transaction. Statements, result sets and database metadata are
 * wrapped too: their getConnection() returns the guarded connection, and a result set's getStatement() returns the
 * same wrapped statement that produced it. Metadata methods themselves stay allowed without a tenant transaction,
 * since Hibernate's startup needs them.
 *
 * <p>This guards only the app_user datasource. The app_admin datasource (Phase 8) is a separate bean with its own rules.
 */
public final class TenantGuardDataSource extends DelegatingDataSource {

    private static final String SET_CONTEXT = "select set_config('app.tenant_id', ?, true),"
            + " set_config('app.user_id', ?, true), set_config('app.role', ?, true)";

    /** JDBC objects that can lead back to a connection, most specific first. */
    private static final List<Class<?>> WRAPPED_TYPES = List.of(
            CallableStatement.class, PreparedStatement.class, Statement.class, ResultSet.class, DatabaseMetaData.class);

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
        Guard guard = new Guard(target);
        Connection guarded = (Connection) Proxy.newProxyInstance(
                TenantGuardDataSource.class.getClassLoader(), new Class<?>[] {TenantGuardedConnection.class}, guard);
        guard.self = guarded;
        return guarded;
    }

    /** Guards one checked-out connection and every JDBC object obtained through it. */
    private final class Guard implements InvocationHandler {

        private final Connection target;
        private final Map<Object, Object> openStatements = new IdentityHashMap<>();
        private Connection self;
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
                case "createStatement", "prepareStatement", "prepareCall" -> requireTenantTransaction("Statement");
                case "commit" -> tenantTransaction = false;
                case "close" -> {
                    tenantTransaction = false;
                    openStatements.clear();
                }
                case "rollback" -> {
                    if (args == null) tenantTransaction = false; // rollback to a savepoint stays in the transaction
                }
                case "setAutoCommit" -> {
                    if ((Boolean) args[0]) tenantTransaction = false;
                }
                case "unwrap", "isWrapperFor" -> {
                    return unwrapOrCheck(proxy, target, method, args, "the underlying connection");
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
            return wrap(call(target, method, args));
        }

        private void requireTenantTransaction(String what) {
            if (!tenantTransaction && !isStartupThreadInWindow()) {
                throw new MissingTenantContextException(what + " refused: no tenant transaction on this connection");
            }
        }

        /**
         * unwrap and isWrapperFor on any guarded object: the guarded object itself is always fine; anything underneath
         * would bypass the guard, so it needs a tenant transaction. Results are returned as they are, so code inside a
         * transaction can still reach driver features (for example PGConnection).
         */
        private Object unwrapOrCheck(Object proxy, Object target, Method method, Object[] args, String what)
                throws Throwable {
            Class<?> type = (Class<?>) args[0];
            if (type.isInstance(proxy)) return method.getName().equals("unwrap") ? proxy : Boolean.TRUE;
            requireTenantTransaction("Unwrap to " + what);
            return call(target, method, args);
        }

        /**
         * Connections map back to the guarded connection. Statements are wrapped once and kept until closed, so a
         * result set's getStatement() returns the same wrapper (Hibernate tracks resources by statement identity).
         * Result sets and metadata get a fresh wrapper. Plain values pass straight through.
         */
        private Object wrap(Object result) {
            if (!(result instanceof Wrapper)) return result;
            if (result instanceof Connection) return self;
            Class<?>[] types = WRAPPED_TYPES.stream()
                    .filter(type -> type.isInstance(result))
                    .toArray(Class<?>[]::new);
            if (types.length == 0) return result;
            if (result instanceof Statement) {
                return openStatements.computeIfAbsent(result, raw -> newProxy(raw, types));
            }
            return newProxy(result, types);
        }

        private Object newProxy(Object raw, Class<?>[] types) {
            return Proxy.newProxyInstance(
                    TenantGuardDataSource.class.getClassLoader(), types, new GuardedJdbcObject(raw));
        }

        /** A statement, result set or metadata object from this connection. */
        private final class GuardedJdbcObject implements InvocationHandler {

            private final Object target;

            GuardedJdbcObject(Object target) {
                this.target = target;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                switch (method.getName()) {
                    case "unwrap", "isWrapperFor" -> {
                        return unwrapOrCheck(proxy, target, method, args, "the underlying JDBC object");
                    }
                    case "getConnection" -> {
                        // Always the guarded connection, even after the pool has closed the object underneath.
                        return self;
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "toString" -> {
                        return "Guarded[" + target + "]";
                    }
                    case "close" -> {
                        Object result = call(target, method, args);
                        if (target instanceof Statement) openStatements.remove(target);
                        return result;
                    }
                    default -> {}
                }
                return wrap(call(target, method, args));
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

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
