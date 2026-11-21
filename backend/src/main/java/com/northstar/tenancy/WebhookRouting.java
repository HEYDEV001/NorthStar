package com.northstar.tenancy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The database calls a webhook needs before, and while, its tenant is known (step 2.2, data-model.md section 12). A
 * webhook arrives with no token and no tenant, so each call here runs in its own narrow tenant-guard mode that sets no
 * context and allows exactly one statement: the allowlisted SECURITY DEFINER function behind it.
 *
 * <p>Routing returns webhook contexts ({@link TenantContext#isWebhook}), the only way to obtain one, so a webhook can
 * only ever be stored in a tenant that owns its phone number ID, or, for a change that names only the business account,
 * its WABA ID (step 2.8a). Inside such a context the guard allows exactly one
 * statement, {@link #INSERT_WEBHOOK_EVENT}; anything else fails before it reaches Postgres.
 */
public interface WebhookRouting {

    /** At most this many phone number or WABA IDs per routing call; the functions ignore any beyond. */
    int MAX_ROUTING_KEYS = 100;

    /**
     * The only statement a transaction in a webhook context may run: store a routed webhook unless its provider event ID
     * is already stored, returning the new row's ID (no row for a duplicate, P1). Parameters, in order: tenant ID (the
     * scope's own), provider, provider event ID, payload as JSON text.
     *
     * <p>It is defined here, beside the guard that compares its exact text, and run by the integrations module, which
     * owns the table. Changing it changes what a webhook context may do: it needs the same review as the guard.
     */
    String INSERT_WEBHOOK_EVENT =
            "insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)"
                    + " values (?, ?, ?, cast(? as jsonb))"
                    + " on conflict (provider, provider_event_id) do nothing returning id";

    /**
     * The tenants owning these WhatsApp phone number IDs, as webhook contexts; unknown IDs are absent. At most
     * {@link #MAX_ROUTING_KEYS} IDs. Calls northstar.whatsapp_tenants(), which returns IDs only.
     */
    Map<String, TenantContext> whatsAppTenants(Set<String> phoneNumberIds);

    /**
     * The tenants owning these WhatsApp Business Account IDs, as webhook contexts; unknown IDs are absent (step 2.8a).
     * Used for webhook changes that name only the WABA, in {@code entry.id}. A WABA belongs to one tenant
     * (whatsapp_accounts_one_tenant_per_waba). At most {@link #MAX_ROUTING_KEYS} IDs. Calls
     * northstar.whatsapp_waba_tenants(), which returns IDs only.
     */
    Map<String, TenantContext> whatsAppTenantsByWaba(Set<String> wabaIds);

    /** Parks a delivery with a bad or missing signature: metadata only (P15). Calls northstar.park_invalid_signature(). */
    Parked parkInvalidSignature(String provider, int bodyBytes);

    /**
     * Parks a verified delivery (or one item of it) for an account no tenant has, with its routing key and payload; the
     * function drops the payload above 64 KB (P16). Calls northstar.park_unknown_account().
     */
    Parked parkUnknownAccount(
            String provider, String providerEventId, String routingKey, int bodyBytes, String payloadJson);

    /** What parking did, and the new row's ID when it stored one. */
    record Parked(Outcome outcome, Optional<UUID> unroutedId) {

        public enum Outcome {
            STORED,
            DUPLICATE,
            /** The database's per-minute cap across every instance was reached; nothing was stored. */
            RATE_LIMITED
        }
    }
}
