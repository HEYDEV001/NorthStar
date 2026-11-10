package com.northstar.jobs;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only: a controller inside the jobs package, the one place allowed to build the system context, that tries to
 * run as the system while serving a request with no tenant scope (as a membership-optional endpoint would).
 * TenantScope must refuse it.
 */
@RestController
class SystemContextTestEndpoints {

    static final AtomicBoolean RAN_AS_SYSTEM = new AtomicBoolean();

    @PostMapping("/test/run-as-system")
    String runAsSystem() {
        TenantScope.run(TenantContext.system(UUID.randomUUID()), () -> RAN_AS_SYSTEM.set(true));
        return "ran";
    }
}
