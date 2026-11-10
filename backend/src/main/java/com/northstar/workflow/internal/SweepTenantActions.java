package com.northstar.workflow.internal;

import com.northstar.jobs.TenantJobRequest;
import java.util.UUID;

/** Queues one tenant's due actions: a safety net for jobs lost to a crash, expired leases and approvals. */
public record SweepTenantActions(UUID tenantId) implements TenantJobRequest {

    @Override
    public Class<TenantActionSweeper> getJobRequestHandler() {
        return TenantActionSweeper.class;
    }
}
