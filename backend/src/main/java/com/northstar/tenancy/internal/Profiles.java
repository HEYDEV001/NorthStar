package com.northstar.tenancy.internal;

import com.northstar.api.NotFoundException;
import com.northstar.tenancy.TenantScope;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed-in member's own profile (step 1.8): name and phone only. The user is always the request's own, from the
 * verified token; RLS and the column grants allow nothing else. avatar_path waits for file storage.
 */
@Service
class Profiles {

    private final UserProfileRepository profiles;
    private final TransactionTemplate transactions;

    Profiles(UserProfileRepository profiles, PlatformTransactionManager transactionManager) {
        this.profiles = profiles;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    ProfileView updateOwn(UpdateProfileRequest request) {
        UUID userId = TenantScope.require().userId();
        return transactions.execute(status -> {
            UserProfile profile =
                    profiles.findById(userId).orElseThrow(() -> new NotFoundException("user_profile", userId));
            String fullName =
                    request.fullName() == null ? null : request.fullName().trim();
            profile.update(fullName == null || fullName.isEmpty() ? null : fullName, request.phone());
            UserProfile saved = profiles.saveAndFlush(profile);
            return new ProfileView(saved.getFullName(), saved.getPhone());
        });
    }
}
