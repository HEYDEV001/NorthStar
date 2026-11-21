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
 * Every hour: asks the allowlisted lookup which tenants have WhatsApp connected and a number (IDs only) and queues one
 * {@link RequestWhatsAppSync} per tenant for the current 6-hour block of UTC (step 2.8a). The job ID and the action's
 * idempotency key both repeat within a block, so each tenant gets one scheduled sync per block, created at the first
 * hourly run in it: a worker restart neither skips a block nor syncs twice in one.
 */
@Component
public class WhatsAppSyncScheduler extends PlatformJobHandler<ScheduleWhatsAppSyncs> {

    static final Duration INTERVAL = Duration.ofHours(1);

    private final TenantDirectory tenants;
    private final Jobs jobs;

    WhatsAppSyncScheduler(TenantDirectory tenants, Jobs jobs) {
        this.tenants = tenants;
        this.jobs = jobs;
    }

    @Override
    protected void runForPlatform(ScheduleWhatsAppSyncs request) {
        schedule(Instant.now());
    }

    /** Queues each tenant's sync for the block this instant falls in. */
    void schedule(Instant now) {
        long block = WhatsAppSyncRequests.block(now, WhatsAppSyncRequests.SCHEDULED_EVERY);
        for (UUID tenantId : tenants.tenantsWithWhatsApp()) {
            jobs.enqueue(jobIdFor(tenantId, block), new RequestWhatsAppSync(tenantId, block));
        }
    }

    static UUID jobIdFor(UUID tenantId, long block) {
        return Jobs.jobId("request-whatsapp-sync", tenantId, block);
    }

    @Bean
    static RecurringPlatformJob whatsAppSyncSchedule() {
        return new RecurringPlatformJob() {
            @Override
            public String id() {
                return "schedule-whatsapp-syncs";
            }

            @Override
            public Duration interval() {
                return INTERVAL;
            }

            @Override
            public PlatformJobRequest request() {
                return new ScheduleWhatsAppSyncs();
            }
        };
    }
}
