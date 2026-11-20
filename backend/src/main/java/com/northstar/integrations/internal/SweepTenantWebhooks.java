package com.northstar.integrations.internal;

import com.northstar.jobs.TenantJobRequest;
import java.util.UUID;

/**
 * One tenant's webhook sweep (step 2.4a): a safety net for processing jobs lost between the commit and their creation,
 * workers that died mid-job, failures waiting for their next retry, and rows stored before processing existed. IDs only.
 */
public record SweepTenantWebhooks(UUID tenantId) implements TenantJobRequest {

    @Override
    public Class<TenantWebhookSweeper> getJobRequestHandler() {
        return TenantWebhookSweeper.class;
    }
}
