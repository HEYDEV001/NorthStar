package com.northstar.integrations.internal;

import com.northstar.jobs.TenantJobRequest;
import java.util.UUID;

/** One tenant's scheduled WhatsApp sync for a 6-hour block (step 2.8a): creates its sync action. IDs only. */
public record RequestWhatsAppSync(UUID tenantId, long block) implements TenantJobRequest {

    @Override
    public Class<RequestWhatsAppSyncHandler> getJobRequestHandler() {
        return RequestWhatsAppSyncHandler.class;
    }
}
