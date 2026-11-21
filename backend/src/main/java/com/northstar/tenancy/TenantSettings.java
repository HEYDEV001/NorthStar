package com.northstar.tenancy;

import java.util.List;

/**
 * The current tenant's settings that other modules act on (data-model.md section 3). Every method reads the tenant of
 * the caller's scope, inside the caller's transaction (none is opened here).
 */
public interface TenantSettings {

    /**
     * The business's own opt-out keywords (step 2.5), as stored: not normalised, possibly empty. The platform's keywords
     * are not included. Keywords are message text a business chose, so they are never logged.
     */
    List<String> optOutKeywords();
}
