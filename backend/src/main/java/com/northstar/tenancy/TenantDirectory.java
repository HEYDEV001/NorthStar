package com.northstar.tenancy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What background jobs need to know about tenants before they have, or to choose, a tenant scope (step 1.10), and who
 * the current member is.
 */
public interface TenantDirectory {

    /** Whether the current scope's tenant exists. Requires a tenant scope; RLS shows only that tenant's own row. */
    boolean currentTenantExists();

    /** The current member's active membership in the scope's tenant; empty for the system and webhook actors. */
    Optional<UUID> currentMembershipId();

    /**
     * Whether this is an active membership of the scope's tenant, holding it with a shared row lock until the caller's
     * transaction ends, so it cannot be deactivated before what the caller assigns to it commits (step 2.10: an admin
     * assigning a manual lead). False for a missing, invited or deactivated membership and another tenant's, alike.
     * Runs only inside the caller's transaction, as an admin.
     */
    boolean lockActiveMembership(UUID membershipId);

    /**
     * Tenants with actions due to run: pending and past their next attempt, or processing with an expired lease. Runs
     * without a tenant scope through the allowlisted northstar.tenants_with_due_actions(), which returns IDs only.
     */
    List<UUID> tenantsWithDueActions();

    /**
     * Tenants with webhook events still unprocessed more than {@code olderThan} after they were received, measured on
     * the database's clock (step 2.4a). Runs without a tenant scope through the allowlisted
     * northstar.tenants_with_unprocessed_webhooks(), which returns IDs only.
     */
    List<UUID> tenantsWithUnprocessedWebhooks(Duration olderThan);

    /**
     * Tenants whose WhatsApp integration is connected with a credential and who have at least one WhatsApp number: the
     * ones the scheduled WhatsApp sync syncs (step 2.8a). Runs without a tenant scope through the allowlisted
     * northstar.tenants_with_whatsapp(), which returns IDs only.
     */
    List<UUID> tenantsWithWhatsApp();
}
