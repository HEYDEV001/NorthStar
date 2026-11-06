package com.northstar.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a unit of work runs as: the tenant, the user and their role. It comes only from the verified token and the
 * membership lookup (or, for workers, the job's tenant), never from anything the client sends. Every transaction sets
 * it as app.tenant_id, app.user_id and app.role (data-model.md section 12).
 */
public record TenantContext(UUID tenantId, UUID userId, Role role) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
    }
}
