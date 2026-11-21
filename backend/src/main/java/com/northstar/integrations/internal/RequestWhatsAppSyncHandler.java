package com.northstar.integrations.internal;

import com.northstar.jobs.TenantJobHandler;
import com.northstar.tenancy.TenantDirectory;
import org.springframework.stereotype.Component;

/**
 * Creates one tenant's scheduled sync action for its block (step 2.8a), as the system actor in that tenant. Running it
 * twice for the same block finds the action it created the first time (P1).
 */
@Component
public class RequestWhatsAppSyncHandler extends TenantJobHandler<RequestWhatsAppSync> {

    private final WhatsAppSyncRequests syncs;

    RequestWhatsAppSyncHandler(TenantDirectory tenants, WhatsAppSyncRequests syncs) {
        super(tenants);
        this.syncs = syncs;
    }

    @Override
    protected void runForTenant(RequestWhatsAppSync request) {
        syncs.requestScheduled(request.block());
    }
}
