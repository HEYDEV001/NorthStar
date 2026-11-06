package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantScope;
import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Gives Hibernate the tenant for @TenantId entities from the same {@link TenantScope} the transaction manager uses, so
 * Hibernate filters reads and fills tenant_id on insert as an application-level layer on top of RLS. No context, no
 * session.
 *
 * <p>The one exception is the guard's startup window: Spring Data opens sessions on the startup thread to inspect
 * repository queries, with no tenant. Those sessions get {@link #NO_TENANT}, which matches no row, and never read
 * tenant data. Once the window closes, a session without context fails.
 */
final class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    /** The nil UUID; gen_random_uuid() never produces it, so no tenant has it. */
    static final UUID NO_TENANT = new UUID(0, 0);

    private final TenantGuardDataSource guard;

    TenantIdentifierResolver(TenantGuardDataSource guard) {
        this.guard = guard;
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        if (TenantScope.current().isEmpty() && guard.isStartupThreadInWindow()) return NO_TENANT;
        return TenantScope.require().tenantId();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
