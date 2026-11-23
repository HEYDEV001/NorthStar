package com.northstar.tenancy.internal;

import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TenantDirectory}. The due-action scan, the unprocessed-webhook scan and the WhatsApp tenant scan (step 2.8a)
 * each run in their own guard scan mode: no context is set and the call to the allowlisted function is the only
 * statement allowed, so nothing else can run without a tenant.
 */
@Service
class TenantDirectoryService implements TenantDirectory {

    // Row locks also apply the update policy, which is admin-only on memberships: a staff member's lock finds nothing.
    private static final String LOCK_ACTIVE_MEMBERSHIP = """
            select id from northstar.memberships
            where tenant_id = ? and id = ? and status = 'active'
            for share""";

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    TenantDirectoryService(
            TenantRepository tenants,
            MembershipRepository memberships,
            DataSource dataSource,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public boolean currentTenantExists() {
        UUID tenantId = TenantScope.require().tenantId();
        return Boolean.TRUE.equals(readOnly.execute(status -> tenants.existsById(tenantId)));
    }

    @Override
    public Optional<UUID> currentMembershipId() {
        TenantContext context = TenantScope.require();
        if (!context.hasUser()) return Optional.empty();
        return readOnly.execute(status -> memberships.findByUserId(context.userId()).stream()
                .filter(membership -> Members.ACTIVE.equals(membership.getStatus()))
                .map(Membership::getId)
                .findFirst());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActiveMembership(UUID membershipId) {
        Objects.requireNonNull(membershipId, "membershipId");
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN) throw new IllegalStateException("Only admins lock another membership");
        return jdbc.sql(LOCK_ACTIVE_MEMBERSHIP)
                .param(context.tenantId())
                .param(membershipId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    @Override
    public List<UUID> tenantsWithDueActions() {
        return scanTenantIds(
                "Due-action scan",
                TenantGuardDataSource.DUE_ACTION_TENANTS,
                TenantGuardedConnection::beginDueActionScan,
                statement -> {});
    }

    @Override
    public List<UUID> tenantsWithUnprocessedWebhooks(Duration olderThan) {
        if (olderThan == null || olderThan.isNegative()) {
            throw new IllegalArgumentException("olderThan must be zero or positive");
        }
        return scanTenantIds(
                "Unprocessed-webhook scan",
                TenantGuardDataSource.UNPROCESSED_WEBHOOK_TENANTS,
                TenantGuardedConnection::beginUnprocessedWebhookScan,
                statement -> statement.setString(1, olderThan.toString()));
    }

    @Override
    public List<UUID> tenantsWithWhatsApp() {
        return scanTenantIds(
                "WhatsApp tenant scan",
                TenantGuardDataSource.WHATSAPP_TENANTS,
                TenantGuardedConnection::beginWhatsAppTenantScan,
                statement -> {});
    }

    /** Puts a guarded connection into one of its tenant-scan modes. */
    @FunctionalInterface
    private interface ScanMode {
        void begin(TenantGuardedConnection connection) throws SQLException;
    }

    /** Binds the scan's parameters, if any. */
    @FunctionalInterface
    private interface Parameters {
        void bind(PreparedStatement statement) throws SQLException;
    }

    /**
     * Runs one of the allowlisted tenant scans in its own guard mode, which sets no context and allows only that
     * statement, and returns the tenant IDs it lists.
     */
    private List<UUID> scanTenantIds(String what, String sql, ScanMode mode, Parameters parameters) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                mode.begin(connection.unwrap(TenantGuardedConnection.class));
                List<UUID> tenantIds = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    parameters.bind(statement);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) tenantIds.add(rows.getObject("tenant_id", UUID.class));
                    }
                }
                connection.commit();
                return tenantIds;
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
                throw new DataAccessResourceFailureException(what + " failed", e);
            }
            throw new UncategorizedSQLException(what, sql, e);
        }
    }
}
