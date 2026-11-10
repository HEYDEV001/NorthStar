package com.northstar.workflow.internal;

import com.northstar.tenancy.TenantContext;
import java.util.UUID;

/** Test-only: code outside the jobs package trying to act as the system. */
public final class SystemContextProbe {

    private SystemContextProbe() {}

    public static TenantContext tryToActAsSystem() {
        return TenantContext.system(UUID.randomUUID());
    }
}
