package com.northstar.integrations.internal;

import com.northstar.jobs.PlatformJobRequest;

/**
 * The recurring webhook sweep across tenants (step 2.4a): finds which tenants have webhook events still unprocessed
 * past the grace period and queues a {@link SweepTenantWebhooks} for each.
 */
public record SweepUnprocessedWebhooks() implements PlatformJobRequest {

    @Override
    public Class<UnprocessedWebhookSweeper> getJobRequestHandler() {
        return UnprocessedWebhookSweeper.class;
    }
}
