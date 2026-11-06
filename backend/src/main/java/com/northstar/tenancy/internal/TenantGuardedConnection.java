package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;

/** A connection from {@link TenantGuardDataSource}: it refuses statements until a tenant transaction is begun on it. */
interface TenantGuardedConnection extends Connection {

    /**
     * Sets app.tenant_id, app.user_id and app.role for the current transaction on this connection, with bind
     * parameters, and allows statements until the transaction ends.
     */
    void beginTenantTransaction(TenantContext context) throws SQLException;
}
