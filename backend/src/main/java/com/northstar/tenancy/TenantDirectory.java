package com.northstar.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What background jobs need to know about tenants before they have, or to choose, a tenant scope (step 1.10). */
public interface TenantDirectory {

    /** Whether the current scope's tenant exists. Requires a tenant scope; RLS shows only that tenant's own row. */
    boolean currentTenantExists();

    /** The current member's active membership in the scope's tenant; empty for the system actor. */
    Optional<UUID> currentMembershipId();

    /**
     * Tenants with actions due to run: pending and past their next attempt, or processing with an expired lease. Runs
     * without a tenant scope through the allowlisted northstar.tenants_with_due_actions(), which returns IDs only.
     */
    List<UUID> tenantsWithDueActions();
}
