package com.northstar.workflow.internal;

import com.northstar.jobs.PlatformJobRequest;

/** The recurring sweep across tenants: finds which tenants have due actions and queues a sweep for each. */
public record SweepDueActions() implements PlatformJobRequest {

    @Override
    public Class<DueActionSweeper> getJobRequestHandler() {
        return DueActionSweeper.class;
    }
}
