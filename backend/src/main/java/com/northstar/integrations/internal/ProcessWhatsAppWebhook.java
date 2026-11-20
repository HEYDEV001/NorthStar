package com.northstar.integrations.internal;

import com.northstar.jobs.Jobs;
import com.northstar.jobs.TenantJobRequest;
import java.util.UUID;

/** Processes one stored WhatsApp webhook event (step 2.2 queues it; 2.4 processes it). IDs only. */
public record ProcessWhatsAppWebhook(UUID tenantId, UUID webhookEventId) implements TenantJobRequest {

    /** One job per stored event, so enqueueing it again creates nothing. */
    static UUID jobIdFor(UUID webhookEventId) {
        return Jobs.jobId("whatsapp-webhook", webhookEventId);
    }

    /**
     * The sweep's job for an event still unprocessed at this step of the retry schedule (step 2.4a,
     * {@link WebhookRetrySchedule}). JobRunr's own retries are off and it skips an ID it already has, so each retry needs
     * an ID of its own; one per step means each step queues at most one job however often the sweep runs.
     */
    static UUID retryJobIdFor(UUID webhookEventId, int step) {
        return Jobs.jobId("whatsapp-webhook", webhookEventId, "retry", step);
    }

    @Override
    public Class<ProcessWhatsAppWebhookHandler> getJobRequestHandler() {
        return ProcessWhatsAppWebhookHandler.class;
    }
}
