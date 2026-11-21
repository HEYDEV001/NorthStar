package com.northstar.integrations.internal;

import com.northstar.tenancy.TenantScope;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The current tenant's webhook events still waiting to be processed, as the sweep reads and closes them (step 2.4a).
 * Native SQL with bind parameters and an explicit tenant filter on top of RLS (D7); ages are measured on the database's
 * clock, which stamped received_at. Every WhatsApp row (messages; statuses since step 2.7; account-level changes since
 * step 2.8a): a kind the job does not process is closed by it with a fixed code, so none waits forever.
 *
 * <p>Callers run inside a tenant transaction as the system actor.
 */
@Component
class UnprocessedWebhookEvents {

    private static final String WAITING = """
            select id, error is not null as failed,
                   floor(extract(epoch from (now() - received_at)) * 1000)::bigint as age_millis
            from northstar.webhook_events
            where tenant_id = ?
              and processed_at is null
              and provider = 'whatsapp'
              and received_at < now() - cast(? as interval)
            order by received_at, id
            limit ?""";

    private static final String CLOSE = """
            update northstar.webhook_events
            set processed_at = now()
            where tenant_id = ?
              and id = ?
              and processed_at is null
              and error is not null
              and received_at <= now() - cast(? as interval)
            returning error""";

    /** One row waiting to be processed: whether an attempt has failed, and its age. */
    record Waiting(UUID id, boolean failed, Duration age) {}

    private final JdbcClient jdbc;

    UnprocessedWebhookEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The oldest waiting rows received more than {@code olderThan} ago, oldest first, at most {@code limit}. */
    List<Waiting> waiting(Duration olderThan, int limit) {
        return jdbc.sql(WAITING)
                .param(TenantScope.require().tenantId())
                .param(olderThan.toString())
                .param(limit)
                .query((row, index) -> new Waiting(
                        row.getObject("id", UUID.class),
                        row.getBoolean("failed"),
                        Duration.ofMillis(row.getLong("age_millis"))))
                .list();
    }

    /**
     * Closes a failed row at least {@code retryWindow} old: sets processed_at and keeps its error code, which it
     * returns. Empty when the row was processed, has no failure recorded or is younger, meanwhile.
     */
    Optional<String> closeFailed(UUID id, Duration retryWindow) {
        return jdbc.sql(CLOSE)
                .param(TenantScope.require().tenantId())
                .param(id)
                .param(retryWindow.toString())
                .query(String.class)
                .optional();
    }
}
