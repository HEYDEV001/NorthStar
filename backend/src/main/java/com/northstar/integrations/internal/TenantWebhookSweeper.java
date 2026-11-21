package com.northstar.integrations.internal;

import com.northstar.jobs.Jobs;
import com.northstar.jobs.TenantJobHandler;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One tenant's webhook sweep, run by the worker as the system actor in that tenant (step 2.4a). Reads the tenant's
 * oldest unprocessed WhatsApp events (messages, statuses since step 2.7 and account-level changes since step 2.8a) past
 * the grace period and, as {@link WebhookRetrySchedule} decides, queues
 * each again under its step's job ID or closes a failed one that is past the retry window. Jobs are created after the
 * transaction commits ({@link Jobs}).
 *
 * <p>A closed row is a final failure, so it gets an ERROR line (the alert, until Phase 8's admin view of webhook
 * errors). Logs carry counts, webhook event IDs and fixed error codes only: never a payload, wamid or phone number.
 */
@Component
public class TenantWebhookSweeper extends TenantJobHandler<SweepTenantWebhooks> {

    private static final Logger log = LoggerFactory.getLogger(TenantWebhookSweeper.class);

    /** At most this many rows per sweep, oldest first; a full batch is logged as a backlog. */
    static final int BATCH = 2000;

    private final UnprocessedWebhookEvents events;
    private final Jobs jobs;
    private final TransactionTemplate transactions;

    TenantWebhookSweeper(
            TenantDirectory tenants,
            UnprocessedWebhookEvents events,
            Jobs jobs,
            PlatformTransactionManager transactionManager) {
        super(tenants);
        this.events = events;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What one sweep did. */
    private record Swept(int waiting, int retried, List<Closed> closed) {}

    private record Closed(UUID webhookEventId, String error) {}

    @Override
    protected void runForTenant(SweepTenantWebhooks request) {
        UUID tenantId = TenantScope.require().tenantId();
        Swept swept = transactions.execute(status -> {
            List<UnprocessedWebhookEvents.Waiting> waiting = events.waiting(WebhookRetrySchedule.GRACE, BATCH);
            int retried = 0;
            List<Closed> closed = new ArrayList<>();
            for (UnprocessedWebhookEvents.Waiting row : waiting) {
                switch (WebhookRetrySchedule.decide(row.failed(), row.age())) {
                    case WebhookRetrySchedule.Retry retry -> {
                        jobs.enqueue(
                                ProcessWhatsAppWebhook.retryJobIdFor(row.id(), retry.step()),
                                new ProcessWhatsAppWebhook(tenantId, row.id()));
                        retried++;
                    }
                    case WebhookRetrySchedule.Close close -> {
                        Optional<String> error = events.closeFailed(row.id(), WebhookRetrySchedule.RETRY_WINDOW);
                        error.ifPresent(code -> closed.add(new Closed(row.id(), code)));
                    }
                    case WebhookRetrySchedule.Wait wait -> {}
                }
            }
            return new Swept(waiting.size(), retried, List.copyOf(closed));
        });

        for (Closed row : swept.closed()) {
            log.error(
                    "webhook.failed provider=whatsapp webhook_event_id={} error={} final=true",
                    row.webhookEventId(),
                    row.error());
        }
        if (swept.waiting() >= BATCH) {
            log.warn("webhook.sweep_backlog provider=whatsapp batch={}", BATCH);
        }
        log.info(
                "webhook.swept provider=whatsapp waiting={} retried={} closed={}",
                swept.waiting(),
                swept.retried(),
                swept.closed().size());
    }
}
