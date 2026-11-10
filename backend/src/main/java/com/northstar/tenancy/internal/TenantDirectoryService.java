package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TenantDirectory}. The due-action scan runs in the guard's scan mode: no context is set and the call to the
 * allowlisted function is the only statement allowed, so nothing else can run without a tenant.
 */
@Service
class TenantDirectoryService implements TenantDirectory {

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final DataSource dataSource;
    private final TransactionTemplate readOnly;

    TenantDirectoryService(
            TenantRepository tenants,
            MembershipRepository memberships,
            DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.dataSource = dataSource;
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
        if (context.isSystem()) return Optional.empty();
        return readOnly.execute(status -> memberships.findByUserId(context.userId()).stream()
                .filter(membership -> Members.ACTIVE.equals(membership.getStatus()))
                .map(Membership::getId)
                .findFirst());
    }

    @Override
    public List<UUID> tenantsWithDueActions() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                connection.unwrap(TenantGuardedConnection.class).beginDueActionScan();
                List<UUID> due = new ArrayList<>();
                try (PreparedStatement statement =
                                connection.prepareStatement(TenantGuardDataSource.DUE_ACTION_TENANTS);
                        ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) due.add(rows.getObject("tenant_id", UUID.class));
                }
                connection.commit();
                return due;
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
                throw new DataAccessResourceFailureException("Due-action scan failed", e);
            }
            throw new UncategorizedSQLException("Due-action scan", TenantGuardDataSource.DUE_ACTION_TENANTS, e);
        }
    }
}
