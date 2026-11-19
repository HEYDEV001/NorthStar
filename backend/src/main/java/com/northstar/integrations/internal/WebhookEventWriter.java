package com.northstar.integrations.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.tenancy.WebhookRouting;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Stores a routed webhook in its tenant (step 2.2, P1). Runs inside a webhook context's transaction, where the tenant
 * guard allows exactly this statement ({@link WebhookRouting#INSERT_WEBHOOK_EVENT}, compared by its exact text), so it is
 * plain JDBC with positional parameters rather than a JPA query. The tenant is bound explicitly from the scope, and RLS
 * checks it again.
 */
@Component
class WebhookEventWriter {

    private final JdbcClient jdbc;

    WebhookEventWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The new row's ID, or empty when this provider event ID is already stored (a duplicate stores nothing). */
    Optional<UUID> insertIfNew(String provider, String providerEventId, String payloadJson) {
        TenantContext context = TenantScope.require();
        if (!context.isWebhook()) throw new IllegalStateException("Webhook events are stored in a webhook context");
        return jdbc.sql(WebhookRouting.INSERT_WEBHOOK_EVENT)
                .param(context.tenantId())
                .param(provider)
                .param(providerEventId)
                .param(payloadJson)
                .query(UUID.class)
                .optional();
    }
}
