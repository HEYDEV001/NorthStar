package com.northstar.workflow.internal;

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
 * Every 30 seconds: asks the allowlisted lookup which tenants have due actions (IDs only) and queues one
 * {@link SweepTenantActions} per tenant, which reads that tenant's actions inside its own scope. One sweep job per
 * tenant per interval, so a slow sweep is not queued twice.
 */
@Component
public class DueActionSweeper extends PlatformJobHandler<SweepDueActions> {

    static final Duration INTERVAL = Duration.ofSeconds(30);

    private final TenantDirectory tenants;
    private final Jobs jobs;

    DueActionSweeper(TenantDirectory tenants, Jobs jobs) {
        this.tenants = tenants;
        this.jobs = jobs;
    }

    @Override
    protected void runForPlatform(SweepDueActions request) {
        long interval = Instant.now().getEpochSecond() / INTERVAL.toSeconds();
        for (UUID tenantId : tenants.tenantsWithDueActions()) {
            jobs.enqueue(Jobs.jobId("sweep-tenant-actions", tenantId, interval), new SweepTenantActions(tenantId));
        }
    }

    @Bean
    static RecurringPlatformJob dueActionSweep() {
        return new RecurringPlatformJob() {
            @Override
            public String id() {
                return "sweep-due-actions";
            }

            @Override
            public Duration interval() {
                return INTERVAL;
            }

            @Override
            public PlatformJobRequest request() {
                return new SweepDueActions();
            }
        };
    }
}
