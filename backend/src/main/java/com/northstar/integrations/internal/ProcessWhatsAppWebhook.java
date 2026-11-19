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

    @Override
    public Class<ProcessWhatsAppWebhookHandler> getJobRequestHandler() {
        return ProcessWhatsAppWebhookHandler.class;
    }
}
