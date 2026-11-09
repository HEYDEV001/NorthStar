package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Answers GET /api/v1/me. A member is read inside their tenant scope (their business and their own profile, both
 * visible to them under RLS). A signed-in user without an active membership has no tenant scope, so nothing is read:
 * business and role are null, and so is the name.
 */
@Service
class CurrentUser {

    private final TenantRepository tenants;
    private final UserProfileRepository profiles;
    private final TransactionTemplate readOnly;

    CurrentUser(TenantRepository tenants, UserProfileRepository profiles, PlatformTransactionManager transactions) {
        this.tenants = tenants;
        this.profiles = profiles;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    MeResponse describe(UUID userId, String email) {
        Optional<TenantContext> scope = TenantScope.current();
        if (scope.isEmpty()) return new MeResponse(new MeResponse.UserView(userId, email, null), null, null);

        TenantContext context = scope.get();
        return readOnly.execute(status -> {
            String fullName =
                    profiles.findById(userId).map(UserProfile::getFullName).orElse(null);
            BusinessView business =
                    tenants.findById(context.tenantId()).map(BusinessView::of).orElse(null);
            return new MeResponse(
                    new MeResponse.UserView(userId, email, fullName),
                    business,
                    context.role().databaseValue());
        });
    }
}
