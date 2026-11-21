package com.northstar.integrations.internal;

import com.northstar.jobs.PlatformJobRequest;

/**
 * The recurring WhatsApp sync schedule across tenants (step 2.8a): finds the tenants with WhatsApp connected and queues
 * a {@link RequestWhatsAppSync} for each, for the current 6-hour block.
 */
public record ScheduleWhatsAppSyncs() implements PlatformJobRequest {

    @Override
    public Class<WhatsAppSyncScheduler> getJobRequestHandler() {
        return WhatsAppSyncScheduler.class;
    }
}
