package com.northstar.tenancy.internal;

import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.WebhookRouting;
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
import java.util.UUID;
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
 * <p>The membership lookup at login runs before any tenant is known. {@link MembershipLookup} puts a connection into
 * lookup mode: only app.user_id is set, and the exact {@link #MEMBERSHIP_LOOKUP} statement is the only one allowed.
 * Accepting an invite works the same way: {@link InviteAcceptance} allows only {@link #INVITE_ACCEPTANCE}. The job
 * sweeper's scan for tenants with due actions sets no context at all and allows only {@link #DUE_ACTION_TENANTS}; the
 * webhook sweep's scan for tenants with unprocessed webhook events likewise allows only
 * {@link #UNPROCESSED_WEBHOOK_TENANTS} (step 2.4a), and the scheduled WhatsApp sync's scan for tenants to sync allows
 * only {@link #WHATSAPP_TENANTS} (step 2.8a).
 *
 * <p>Webhooks (step 2.2) arrive with no token and no tenant. Routing a WhatsApp delivery sets no context and allows only
 * {@link #WHATSAPP_ROUTING}, or, for the changes that name only their business account, {@link #WHATSAPP_WABA_ROUTING}
 * (step 2.8a), each in its own mode; parking an unroutable delivery allows only {@link #PARK_INVALID_SIGNATURE} or
 * {@link #PARK_UNKNOWN_ACCOUNT}, each in its own mode. Storing a routed delivery is a tenant transaction in a webhook
 * context ({@link TenantContext#isWebhook}): the full context is set, but the only statement allowed is
 * {@link WebhookRouting#INSERT_WEBHOOK_EVENT}, so a webhook can never read or change anything else in its tenant.
 *
 * <p>This guards only the app_user datasource. The app_admin datasource (Phase 8) is a separate bean with its own rules.
 */
public final class TenantGuardDataSource extends DelegatingDataSource {

    private static final String SET_CONTEXT = "select set_config('app.tenant_id', ?, true),"
            + " set_config('app.user_id', ?, true), set_config('app.role', ?, true)";
    private static final String SET_USER = "select set_config('app.user_id', ?, true)";

    /** The one statement allowed in lookup mode, calling the allowlisted SECURITY DEFINER function. */
    static final String MEMBERSHIP_LOOKUP = "select tenant_id, membership_id, role from northstar.current_membership()";

    /** The one statement allowed in invite-acceptance mode, calling the allowlisted SECURITY DEFINER function. */
    static final String INVITE_ACCEPTANCE =
            "select outcome, tenant_id, membership_id, role from northstar.accept_invite(?, ?)";

    /** The one statement allowed in due-action scan mode, calling the allowlisted SECURITY DEFINER function. */
    static final String DUE_ACTION_TENANTS = "select tenant_id from northstar.tenants_with_due_actions()";

    /**
     * The one statement allowed in unprocessed-webhook scan mode (step 2.4a): tenant IDs only. Its one parameter is the
     * grace period as an ISO-8601 duration, measured on the database's clock.
     */
    static final String UNPROCESSED_WEBHOOK_TENANTS =
            "select tenant_id from northstar.tenants_with_unprocessed_webhooks(cast(? as interval))";

    /** The one statement allowed in WhatsApp routing mode: phone number IDs to tenant IDs, nothing else (step 2.2). */
    static final String WHATSAPP_ROUTING = "select phone_number_id, tenant_id from northstar.whatsapp_tenants(?)";

    /** The one statement allowed in WhatsApp WABA routing mode: WABA IDs to tenant IDs, nothing else (step 2.8a). */
    static final String WHATSAPP_WABA_ROUTING = "select waba_id, tenant_id from northstar.whatsapp_waba_tenants(?)";

    /**
     * The one statement allowed in WhatsApp tenant scan mode (step 2.8a): the tenants with a connected WhatsApp
     * integration and a number, IDs only, for the scheduled sync.
     */
    static final String WHATSAPP_TENANTS = "select tenant_id from northstar.tenants_with_whatsapp()";

    /** The one statement allowed in invalid-signature parking mode (P15): metadata only, no payload parameter. */
    static final String PARK_INVALID_SIGNATURE =
            "select outcome, unrouted_id from northstar.park_invalid_signature(?, ?)";

    /** The one statement allowed in unknown-account parking mode (P16). */
    static final String PARK_UNKNOWN_ACCOUNT =
            "select outcome, unrouted_id from northstar.park_unknown_account(?, ?, ?, ?, cast(? as jsonb))";

    /** What the current transaction on a connection may do. */
    private enum Mode {
        NONE(null),
        TENANT(null),
        MEMBERSHIP_LOOKUP(TenantGuardDataSource.MEMBERSHIP_LOOKUP),
        INVITE_ACCEPTANCE(TenantGuardDataSource.INVITE_ACCEPTANCE),
        DUE_ACTION_SCAN(TenantGuardDataSource.DUE_ACTION_TENANTS),
        UNPROCESSED_WEBHOOK_SCAN(TenantGuardDataSource.UNPROCESSED_WEBHOOK_TENANTS),
        WHATSAPP_ROUTING(TenantGuardDataSource.WHATSAPP_ROUTING),
        WHATSAPP_WABA_ROUTING(TenantGuardDataSource.WHATSAPP_WABA_ROUTING),
        WHATSAPP_TENANT_SCAN(TenantGuardDataSource.WHATSAPP_TENANTS),
        INVALID_SIGNATURE_PARKING(TenantGuardDataSource.PARK_INVALID_SIGNATURE),
        UNKNOWN_ACCOUNT_PARKING(TenantGuardDataSource.PARK_UNKNOWN_ACCOUNT),
        /** A tenant transaction in a webhook context: the full context is set, and only the insert may run. */
        WEBHOOK_EVENT_INSERT(WebhookRouting.INSERT_WEBHOOK_EVENT);

        /** For the single-statement modes: the one statement allowed. */
        private final String onlyStatement;

        Mode(String onlyStatement) {
            this.onlyStatement = onlyStatement;
        }
    }

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
        private Mode mode = Mode.NONE;

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
                case "beginMembershipLookup" -> {
                    beginUserOnly((UUID) args[0], Mode.MEMBERSHIP_LOOKUP);
                    return null;
                }
                case "beginInviteAcceptance" -> {
                    beginUserOnly((UUID) args[0], Mode.INVITE_ACCEPTANCE);
                    return null;
                }
                case "beginDueActionScan" -> {
                    beginWithoutContext(Mode.DUE_ACTION_SCAN);
                    return null;
                }
                case "beginUnprocessedWebhookScan" -> {
                    beginWithoutContext(Mode.UNPROCESSED_WEBHOOK_SCAN);
                    return null;
                }
                case "beginWhatsAppRouting" -> {
                    beginWithoutContext(Mode.WHATSAPP_ROUTING);
                    return null;
                }
                case "beginWhatsAppWabaRouting" -> {
                    beginWithoutContext(Mode.WHATSAPP_WABA_ROUTING);
                    return null;
                }
                case "beginWhatsAppTenantScan" -> {
                    beginWithoutContext(Mode.WHATSAPP_TENANT_SCAN);
                    return null;
                }
                case "beginInvalidSignatureParking" -> {
                    beginWithoutContext(Mode.INVALID_SIGNATURE_PARKING);
                    return null;
                }
                case "beginUnknownAccountParking" -> {
                    beginWithoutContext(Mode.UNKNOWN_ACCOUNT_PARKING);
                    return null;
                }
                case "prepareStatement" -> {
                    boolean theOnlyStatement = mode.onlyStatement != null && mode.onlyStatement.equals(args[0]);
                    if (!theOnlyStatement) requireTenantTransaction("Statement");
                }
                case "createStatement", "prepareCall" -> requireTenantTransaction("Statement");
                case "commit" -> mode = Mode.NONE;
                case "close" -> {
                    mode = Mode.NONE;
                    openStatements.clear();
                }
                case "rollback" -> {
                    if (args == null) mode = Mode.NONE; // rollback to a savepoint stays in the transaction
                }
                case "setAutoCommit" -> {
                    if ((Boolean) args[0]) mode = Mode.NONE;
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
            if (mode == Mode.TENANT || isStartupThreadInWindow()) return;
            if (mode == Mode.WEBHOOK_EVENT_INSERT) {
                throw new MissingTenantContextException(
                        what + " refused: a webhook context may only store webhook events");
            }
            throw new MissingTenantContextException(what + " refused: no tenant transaction on this connection");
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
            mode = context.isWebhook() ? Mode.WEBHOOK_EVENT_INSERT : Mode.TENANT;
        }

        /** The single-statement modes that set no context at all: routing, parking and the tenant scans. */
        private void beginWithoutContext(Mode withoutContext) throws SQLException {
            if (target.getAutoCommit()) {
                throw new IllegalStateException("A context-free call can only run inside a transaction");
            }
            mode = withoutContext;
        }

        private void beginUserOnly(UUID userId, Mode userOnlyMode) throws SQLException {
            if (target.getAutoCommit()) {
                throw new IllegalStateException("A user-only call can only run inside a transaction");
            }
            try (PreparedStatement statement = target.prepareStatement(SET_USER)) {
                statement.setString(1, userId.toString());
                statement.executeQuery().close();
            }
            mode = userOnlyMode;
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
