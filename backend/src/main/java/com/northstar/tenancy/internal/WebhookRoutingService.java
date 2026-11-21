package com.northstar.tenancy.internal;

import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.WebhookRouting;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;

/**
 * {@link WebhookRouting} (step 2.2). Each call is its own short transaction in a single-statement guard mode that sets
 * no context, so nothing but the allowlisted function behind it can run without a tenant. This is the only class that
 * creates webhook contexts ({@link TenantContext} checks the caller), and it creates them only for tenant IDs the
 * routing function returned.
 */
@Service
class WebhookRoutingService implements WebhookRouting {

    private final DataSource dataSource;

    WebhookRoutingService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Map<String, TenantContext> whatsAppTenants(Set<String> phoneNumberIds) {
        if (phoneNumberIds.isEmpty()) return Map.of();
        if (phoneNumberIds.size() > MAX_ROUTING_KEYS) {
            throw new IllegalArgumentException("At most " + MAX_ROUTING_KEYS + " phone number IDs per call");
        }
        Map<String, UUID> tenants = inTransaction(
                TenantGuardDataSource.WHATSAPP_ROUTING,
                TenantGuardedConnection::beginWhatsAppRouting,
                (connection, statement) -> {
                    statement.setArray(1, connection.createArrayOf("text", phoneNumberIds.toArray(String[]::new)));
                    Map<String, UUID> found = new LinkedHashMap<>();
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next())
                            found.put(rows.getString("phone_number_id"), rows.getObject("tenant_id", UUID.class));
                    }
                    return found;
                });
        return webhookContexts(tenants);
    }

    @Override
    public Map<String, TenantContext> whatsAppTenantsByWaba(Set<String> wabaIds) {
        if (wabaIds.isEmpty()) return Map.of();
        if (wabaIds.size() > MAX_ROUTING_KEYS) {
            throw new IllegalArgumentException("At most " + MAX_ROUTING_KEYS + " WABA IDs per call");
        }
        Map<String, UUID> tenants = inTransaction(
                TenantGuardDataSource.WHATSAPP_WABA_ROUTING,
                TenantGuardedConnection::beginWhatsAppWabaRouting,
                (connection, statement) -> {
                    statement.setArray(1, connection.createArrayOf("text", wabaIds.toArray(String[]::new)));
                    Map<String, UUID> found = new LinkedHashMap<>();
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            UUID tenant = rows.getObject("tenant_id", UUID.class);
                            UUID other = found.put(rows.getString("waba_id"), tenant);
                            // The exclusion constraint makes this impossible; routing to either tenant would be a leak.
                            if (other != null && !other.equals(tenant)) {
                                throw new IllegalStateException("A WABA ID routed to two tenants");
                            }
                        }
                    }
                    return found;
                });
        return webhookContexts(tenants);
    }

    /** A webhook context per routed key, for exactly the tenants a routing function returned. */
    private Map<String, TenantContext> webhookContexts(Map<String, UUID> tenants) {
        Map<String, TenantContext> contexts = new LinkedHashMap<>();
        for (Map.Entry<String, UUID> routed : tenants.entrySet()) {
            // Constructed right here, not through a method reference: TenantContext checks that this class is the
            // caller, so only a tenant a routing function returned ever gets a webhook context.
            TenantContext context = new TenantContext(routed.getValue(), TenantContext.SYSTEM_USER_ID, Role.WEBHOOK);
            contexts.put(routed.getKey(), context);
        }
        return contexts;
    }

    @Override
    public Parked parkInvalidSignature(String provider, int bodyBytes) {
        return inTransaction(
                TenantGuardDataSource.PARK_INVALID_SIGNATURE,
                TenantGuardedConnection::beginInvalidSignatureParking,
                (connection, statement) -> {
                    statement.setString(1, provider);
                    statement.setInt(2, bodyBytes);
                    return parked(statement);
                });
    }

    @Override
    public Parked parkUnknownAccount(
            String provider, String providerEventId, String routingKey, int bodyBytes, String payloadJson) {
        return inTransaction(
                TenantGuardDataSource.PARK_UNKNOWN_ACCOUNT,
                TenantGuardedConnection::beginUnknownAccountParking,
                (connection, statement) -> {
                    statement.setString(1, provider);
                    statement.setString(2, providerEventId);
                    statement.setString(3, routingKey);
                    statement.setInt(4, bodyBytes);
                    statement.setString(5, payloadJson);
                    return parked(statement);
                });
    }

    private static Parked parked(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            if (!row.next()) throw new IllegalStateException("Parking returned no outcome");
            Parked.Outcome outcome = switch (row.getString("outcome")) {
                case "stored" -> Parked.Outcome.STORED;
                case "duplicate" -> Parked.Outcome.DUPLICATE;
                case "rate_limited" -> Parked.Outcome.RATE_LIMITED;
                default -> throw new IllegalStateException("Unknown parking outcome");
            };
            return new Parked(outcome, Optional.ofNullable(row.getObject("unrouted_id", UUID.class)));
        }
    }

    /** One transaction: begin the guard mode, prepare its one statement, run it, commit. */
    private <T> T inTransaction(String sql, ModeStart mode, Call<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                mode.begin(connection.unwrap(TenantGuardedConnection.class));
                T result;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    result = work.run(connection, statement);
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            if (connectionFailure(e)) throw new DataAccessResourceFailureException("Webhook routing call failed", e);
            throw new UncategorizedSQLException("Webhook routing call", sql, e);
        }
    }

    /** Only a lost or refused connection means the database is unavailable (503); anything else is a bug (500). */
    private static boolean connectionFailure(SQLException e) {
        return e instanceof SQLTransientConnectionException
                || e instanceof SQLNonTransientConnectionException
                || (e.getSQLState() != null && e.getSQLState().startsWith("08"));
    }

    @FunctionalInterface
    private interface ModeStart {
        void begin(TenantGuardedConnection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface Call<T> {
        T run(Connection connection, PreparedStatement statement) throws SQLException;
    }
}
