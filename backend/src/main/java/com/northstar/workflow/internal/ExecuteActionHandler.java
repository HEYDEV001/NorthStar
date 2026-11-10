package com.northstar.workflow.internal;

import com.northstar.jobs.TenantJobHandler;
import com.northstar.tenancy.TenantDirectory;
import org.springframework.stereotype.Component;

@Component
public class ExecuteActionHandler extends TenantJobHandler<ExecuteAction> {

    private final ActionExecutor executor;

    ExecuteActionHandler(TenantDirectory tenants, ActionExecutor executor) {
        super(tenants);
        this.executor = executor;
    }

    @Override
    protected void runForTenant(ExecuteAction request) {
        executor.execute(request.actionId());
    }
}
