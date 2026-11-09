package com.northstar.tenancy.internal;

import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.GoneException;
import com.northstar.api.RateLimitedException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepting an invite (step 1.9, P12). The invitee has no tenant scope yet, so the call runs in the guard's
 * invite-acceptance mode: only app.user_id is set, and northstar.accept_invite() is the only statement allowed. The
 * function does every check and every write; this class turns its outcome into the API's answer. No error says
 * anything about the invite's email, the business or the tenant, and neither the token nor the email is logged.
 */
@Service
class InviteAcceptance {

    private final DataSource dataSource;
    private final TenantRepository tenants;
    private final AcceptRateLimiter rateLimiter;
    private final TransactionTemplate readOnly;

    InviteAcceptance(
            DataSource dataSource,
            TenantRepository tenants,
            AcceptRateLimiter rateLimiter,
            PlatformTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.tenants = tenants;
        this.rateLimiter = rateLimiter;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    private record Outcome(String outcome, UUID tenantId, UUID membershipId, Role role) {}

    AcceptedInviteResponse accept(UUID userId, String email, String token) {
        if (!rateLimiter.tryAcquire(userId)) throw new RateLimitedException();
        Outcome result = call(userId, email, InviteTokens.hash(token));
        switch (result.outcome()) {
            case "accepted" -> {
                TenantContext member = new TenantContext(result.tenantId(), userId, result.role());
                BusinessView business = TenantScope.call(
                        member,
                        () -> readOnly.execute(status -> tenants.findById(result.tenantId())
                                .map(BusinessView::of)
                                .orElseThrow()));
                return new AcceptedInviteResponse(business, result.role().databaseValue());
            }
            case "not_found" -> throw new InviteNotFoundException();
            case "email_mismatch" ->
                throw new ForbiddenException(
                        "invite_email_mismatch", "This invite was sent to a different email address");
            case "used" -> throw new GoneException("invite_used", "This invite has already been used");
            case "revoked" -> throw new GoneException("invite_revoked", "This invite has been withdrawn");
            case "expired" ->
                throw new GoneException("invite_expired", "This invite has expired; ask your admin to send a new one");
            case "active_elsewhere" ->
                throw new ConflictException("already_has_business", "This account already belongs to a business");
            default -> throw new IllegalStateException("Unknown invite outcome");
        }
    }

    private Outcome call(UUID userId, String email, String tokenHash) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                connection.unwrap(TenantGuardedConnection.class).beginInviteAcceptance(userId);
                Outcome outcome;
                try (PreparedStatement statement =
                        connection.prepareStatement(TenantGuardDataSource.INVITE_ACCEPTANCE)) {
                    statement.setString(1, tokenHash);
                    statement.setString(2, email == null ? "" : email);
                    try (ResultSet row = statement.executeQuery()) {
                        row.next();
                        String role = row.getString("role");
                        outcome = new Outcome(
                                row.getString("outcome"),
                                row.getObject("tenant_id", UUID.class),
                                row.getObject("membership_id", UUID.class),
                                role == null ? null : Role.fromDatabaseValue(role));
                    }
                }
                connection.commit();
                return outcome;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            if (e instanceof SQLTransientConnectionException
                    || e instanceof SQLNonTransientConnectionException
                    || (e.getSQLState() != null && e.getSQLState().startsWith("08"))) {
                throw new DataAccessResourceFailureException("Invite acceptance failed", e);
            }
            throw new UncategorizedSQLException("Invite acceptance", TenantGuardDataSource.INVITE_ACCEPTANCE, e);
        }
    }
}
