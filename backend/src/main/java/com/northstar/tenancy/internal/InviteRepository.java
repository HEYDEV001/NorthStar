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

    /**
     * Sets a pending invite's token hash through northstar.set_invite_token() (step 1.12), which allows it only for the
     * system actor, in the current tenant, on an invite neither accepted, revoked nor expired. True if it changed.
     */
    @Query(nativeQuery = true, value = "select northstar.set_invite_token(:inviteId, :tokenHash)")
    boolean setToken(@Param("inviteId") UUID inviteId, @Param("tokenHash") String tokenHash);

    /** Invites neither accepted nor revoked, oldest first. */
    List<Invite> findByAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtAsc();

    /** The open invite for this email in the current tenant, compared ignoring case. */
    @Query("""
            select i from Invite i
            where lower(i.email) = lower(:email) and i.acceptedAt is null and i.revokedAt is null""")
    Optional<Invite> findOpenForEmail(@Param("email") String email);
}
