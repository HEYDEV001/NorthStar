package com.northstar.integrations.internal;

import com.northstar.jobs.Jobs;
import com.northstar.jobs.PlatformJobHandler;
import com.northstar.jobs.PlatformJobRequest;
import com.northstar.jobs.RecurringPlatformJob;
import com.northstar.tenancy.TenantDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Every minute: asks the allowlisted lookup which tenants have webhook events still unprocessed past the grace period
 * (IDs only) and queues one {@link SweepTenantWebhooks} per tenant, which reads that tenant's rows inside its own scope.
 * One sweep job per tenant per interval, so a slow sweep is not queued twice (step 2.4a, as the action sweeper of step
 * 1.10).
 */
@Component
public class UnprocessedWebhookSweeper extends PlatformJobHandler<SweepUnprocessedWebhooks> {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    private final TenantDirectory tenants;
    private final Jobs jobs;

    UnprocessedWebhookSweeper(TenantDirectory tenants, Jobs jobs) {
        this.tenants = tenants;
        this.jobs = jobs;
    }

    @Override
    protected void runForPlatform(SweepUnprocessedWebhooks request) {
        long interval = Instant.now().getEpochSecond() / INTERVAL.toSeconds();
        for (UUID tenantId : tenants.tenantsWithUnprocessedWebhooks(WebhookRetrySchedule.GRACE)) {
            jobs.enqueue(sweepJobIdFor(tenantId, interval), new SweepTenantWebhooks(tenantId));
        }
    }

    static UUID sweepJobIdFor(UUID tenantId, long interval) {
        return Jobs.jobId("sweep-tenant-webhooks", tenantId, interval);
    }

    @Bean
    static RecurringPlatformJob unprocessedWebhookSweep() {
        return new RecurringPlatformJob() {
            @Override
            public String id() {
                return "sweep-unprocessed-webhooks";
            }

            @Override
            public Duration interval() {
                return INTERVAL;
            }

            @Override
            public PlatformJobRequest request() {
                return new SweepUnprocessedWebhooks();
            }
        };
    }
}
