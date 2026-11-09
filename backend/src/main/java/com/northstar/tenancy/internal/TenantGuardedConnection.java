package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** A connection from {@link TenantGuardDataSource}: it refuses statements until a tenant transaction is begun on it. */
interface TenantGuardedConnection extends Connection {

    /**
     * Sets app.tenant_id, app.user_id and app.role for the current transaction on this connection, with bind
     * parameters, and allows statements until the transaction ends.
     */
    void beginTenantTransaction(TenantContext context) throws SQLException;

    /**
     * Sets only app.user_id for the current transaction and allows exactly one statement, the membership lookup. Used
     * at login, before any tenant is known.
     */
    void beginMembershipLookup(UUID userId) throws SQLException;

    /**
     * Sets only app.user_id for the current transaction and allows exactly one statement, the invite acceptance. Used
     * before the invitee has any tenant context.
     */
    void beginInviteAcceptance(UUID userId) throws SQLException;
}
