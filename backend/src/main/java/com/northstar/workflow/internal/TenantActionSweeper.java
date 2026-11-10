package com.northstar.workflow.internal;

import com.northstar.jobs.TenantJobHandler;
import com.northstar.tenancy.TenantDirectory;
import org.springframework.stereotype.Component;

@Component
public class TenantActionSweeper extends TenantJobHandler<SweepTenantActions> {

    private final ActionExecutor executor;

    TenantActionSweeper(TenantDirectory tenants, ActionExecutor executor) {
        super(tenants);
        this.executor = executor;
    }

    @Override
    protected void runForTenant(SweepTenantActions request) {
        executor.queueDue();
    }
}
