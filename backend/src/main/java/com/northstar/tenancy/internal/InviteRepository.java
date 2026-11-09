package com.northstar.tenancy.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    /**
     * Loads an invite and locks its row (select ... for update). Revoke and resend use it, and accept_invite() locks
     * the same row, so a revoke racing an accept runs one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invite> findLockedById(UUID id);

    /** Invites neither accepted nor revoked, oldest first. */
    List<Invite> findByAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtAsc();

    /** The open invite for this email in the current tenant, compared ignoring case. */
    @Query("""
            select i from Invite i
            where lower(i.email) = lower(:email) and i.acceptedAt is null and i.revokedAt is null""")
    Optional<Invite> findOpenForEmail(@Param("email") String email);
}
