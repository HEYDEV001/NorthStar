package com.northstar.jobs;

import org.jobrunr.jobs.lambdas.JobRequestHandler;

/**
 * Runs a {@link PlatformJobRequest} with no tenant scope. Any repository or transaction it touched would be refused
 * (MissingTenantContextException), so its work is limited to allowlisted tenant-ID lookups and enqueueing tenant jobs.
 */
public abstract class PlatformJobHandler<T extends PlatformJobRequest> implements JobRequestHandler<T> {

    @Override
    public final void run(T request) {
        runForPlatform(request);
    }

    protected abstract void runForPlatform(T request);
}
