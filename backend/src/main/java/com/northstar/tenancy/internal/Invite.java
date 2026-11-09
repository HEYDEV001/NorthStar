package com.northstar.tenancy.internal;

import com.northstar.tenancy.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.TenantId;

/**
 * A staff invite (northstar.invites, data-model.md section 3). Only revoked_at ever changes through the app, matching
 * the column grant; acceptance is done by the accept_invite() function. Only the token's hash is stored.
 */
@Entity
@Table(schema = "northstar", name = "invites")
@DynamicInsert
@DynamicUpdate
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Convert(converter = RoleConverter.class)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(nullable = false, updatable = false)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private UUID invitedBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant expiresAt;

    @Column(insertable = false, updatable = false)
    private Instant acceptedAt;

    @Column(insertable = false)
    private Instant revokedAt;

    protected Invite() {}

    Invite(String email, Role role, String tokenHash, UUID invitedBy) {
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    boolean isAccepted() {
        return acceptedAt != null;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** "pending" or "expired"; only for invites neither accepted nor revoked. */
    String openStatus(Instant now) {
        return isExpired(now) ? "expired" : "pending";
    }

    void revoke(Instant at) {
        this.revokedAt = at;
    }
}
