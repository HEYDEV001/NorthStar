package com.northstar.workflow.internal;

import com.northstar.jobs.TenantJobRequest;
import java.util.UUID;

/** Runs one attempt at an action, if it is due when the job runs. IDs only. */
public record ExecuteAction(UUID tenantId, UUID actionId) implements TenantJobRequest {

    @Override
    public Class<ExecuteActionHandler> getJobRequestHandler() {
        return ExecuteActionHandler.class;
    }
}
