package com.northstar.integrations.internal;

import com.northstar.jobs.TenantJobHandler;
import com.northstar.tenancy.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job for a stored WhatsApp webhook event, run by the worker as the system actor in the event's tenant.
 *
 * <p>Step 2.2 only confirms the row is there, in this tenant, and logs {@code webhook.queued}; processed_at stays null.
 * Step 2.4 replaces this with the processing itself (normalise to an engine event, contact, conversation, lead) and adds
 * a sweep for rows with processed_at still null, which also covers events stored before then and an event whose job
 * was lost between the commit and its creation.
 */
@Component
public class ProcessWhatsAppWebhookHandler extends TenantJobHandler<ProcessWhatsAppWebhook> {

    private static final Logger log = LoggerFactory.getLogger(ProcessWhatsAppWebhookHandler.class);

    private final WebhookEventRepository events;
    private final TransactionTemplate readOnly;

    ProcessWhatsAppWebhookHandler(
            TenantDirectory tenants, WebhookEventRepository events, PlatformTransactionManager transactionManager) {
        super(tenants);
        this.events = events;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    protected void runForTenant(ProcessWhatsAppWebhook request) {
        boolean stored = Boolean.TRUE.equals(readOnly.execute(status -> events.existsById(request.webhookEventId())));
        if (!stored) {
            // The job exists only if its row does, in the same tenant; anything else is a bug, and it fails loudly.
            log.error("webhook.event_missing webhook_event_id={}", request.webhookEventId());
            throw new IllegalStateException("Webhook event not found in the job's tenant");
        }
        log.info("webhook.queued provider=whatsapp webhook_event_id={}", request.webhookEventId());
    }
}
