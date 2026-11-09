package com.northstar.tenancy.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByUserId(UUID userId);

    /**
     * Loads a membership and locks its row until the transaction ends (select ... for update), so concurrent changes to
     * the same member run one after another, each deciding from the committed row. RLS and @TenantId still apply: a
     * row from another tenant is not found, and the lock needs the admin-only update policy to pass.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Membership> findLockedById(UUID id);
}
