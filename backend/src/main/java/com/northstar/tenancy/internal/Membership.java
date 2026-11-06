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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

/**
 * A user's access to one tenant (northstar.memberships, data-model.md section 3). Hibernate fills and filters tenant_id
 * from the tenant context; RLS enforces the same in the database. Foreign keys are plain IDs, with no associations.
 */
@Entity
@Table(schema = "northstar", name = "memberships")
@DynamicUpdate
public class Membership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Convert(converter = RoleConverter.class)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private String status;

    private String whatsappPhone;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Membership() {}

    public Membership(UUID userId, Role role, String status) {
        this.userId = userId;
        this.role = role;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public String getStatus() {
        return status;
    }
}
