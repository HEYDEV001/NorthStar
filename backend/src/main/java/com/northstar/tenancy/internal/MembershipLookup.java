package com.northstar.tenancy.internal;

import com.northstar.tenancy.Role;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Component;

/**
 * Finds the user's active membership at the start of every request, before any tenant is known (step 1.5). It runs in
 * its own short transaction in the guard's lookup mode: only app.user_id is set, and the call to
 * northstar.current_membership() is the only statement allowed. There is no ambient user-only scope, so nothing else
 * can run in it. Never cached: deactivation takes effect on the next request (P13).
 */
@Component
public class MembershipLookup {

    /** The user's active membership: the tenant, the membership and the role every later transaction runs as. */
    public record ActiveMembership(UUID tenantId, UUID membershipId, Role role) {}

    private final DataSource dataSource;

    MembershipLookup(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<ActiveMembership> activeMembershipOf(UUID userId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ActiveMembership> membership = lookUp(connection, userId);
                connection.commit();
                return membership;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            if (connectionFailure(e)) throw new DataAccessResourceFailureException("Membership lookup failed", e);
            throw new UncategorizedSQLException("Membership lookup", TenantGuardDataSource.MEMBERSHIP_LOOKUP, e);
        }
    }

    /** Only a lost or refused connection means the database is unavailable (503); anything else is a bug (500). */
    private static boolean connectionFailure(SQLException e) {
        return e instanceof SQLTransientConnectionException
                || e instanceof SQLNonTransientConnectionException
                || (e.getSQLState() != null && e.getSQLState().startsWith("08"));
    }

    private static Optional<ActiveMembership> lookUp(Connection connection, UUID userId) throws SQLException {
        connection.unwrap(TenantGuardedConnection.class).beginMembershipLookup(userId);
        try (PreparedStatement statement = connection.prepareStatement(TenantGuardDataSource.MEMBERSHIP_LOOKUP);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) return Optional.empty();
            return Optional.of(new ActiveMembership(
                    result.getObject("tenant_id", UUID.class),
                    result.getObject("membership_id", UUID.class),
                    Role.fromDatabaseValue(result.getString("role"))));
        }
    }
}
