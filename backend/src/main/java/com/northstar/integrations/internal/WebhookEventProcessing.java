package com.northstar.integrations.internal;

import com.northstar.tenancy.TenantScope;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A stored webhook event's processing state, as its job reads and records it (step 2.4b; data-model.md section 9):
 * processed_at null and error null, not processed yet; processed_at null and error set, an attempt failed and the sweep
 * retries it; processed_at set, done for good, with a code when it was not processed in full. Native SQL with bind
 * parameters and an explicit tenant filter on top of RLS (D7). Callers run inside a tenant transaction as the system
 * actor.
 */
@Component
class WebhookEventProcessing {

    // The row lock serialises runs of one event's job: a second run waits, then sees processed_at set (P1).
    private static final String LOCK = """
            select provider_event_id, payload::text as payload, processed_at is not null as processed
            from northstar.webhook_events
            where tenant_id = ? and id = ? and provider = ?
            for update""";

    private static final String FINISH = """
            update northstar.webhook_events set processed_at = now(), error = ?
            where tenant_id = ? and id = ? and processed_at is null""";

    private static final String RECORD_RETRYABLE_FAILURE = """
            update northstar.webhook_events set error = ?
            where tenant_id = ? and id = ? and processed_at is null""";

    /** A locked row: its provider event ID, its payload (a provider slice, never logged) and whether it is done. */
    record Locked(String providerEventId, String payloadJson, boolean processed) {

        @Override
        public String toString() {
            return "Locked[processed=" + processed + "]";
        }
    }

    private final JdbcClient jdbc;

    WebhookEventProcessing(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The current tenant's event of this provider, locked until the transaction ends; empty if it has none. */
    Optional<Locked> lock(UUID webhookEventId, String provider) {
        return jdbc.sql(LOCK)
                .param(tenant())
                .param(Objects.requireNonNull(webhookEventId, "webhookEventId"))
                .param(Objects.requireNonNull(provider, "provider"))
                .query((row, index) -> new Locked(
                        row.getString("provider_event_id"), row.getString("payload"), row.getBoolean("processed")))
                .optional();
    }

    /**
     * Marks the event processed for good: with no code when it was processed in full (clearing any earlier attempt's),
     * or with the fixed code that says why not.
     */
    void finish(UUID webhookEventId, String errorCode) {
        jdbc.sql(FINISH).param(errorCode).param(tenant()).param(webhookEventId).update();
    }

    /** Records a failed attempt that the sweep retries; false if the event was processed meanwhile. */
    boolean recordRetryableFailure(UUID webhookEventId, String errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        return jdbc.sql(RECORD_RETRYABLE_FAILURE)
                        .param(errorCode)
                        .param(tenant())
                        .param(webhookEventId)
                        .update()
                == 1;
    }

    private static UUID tenant() {
        return TenantScope.require().tenantId();
    }
}
