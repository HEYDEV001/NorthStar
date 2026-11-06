package com.northstar.tenancy.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A client business (northstar.tenants, data-model.md section 3). Platform-wide, so no @TenantId; RLS limits app_user
 * to the row where id = app.tenant_id. Columns app_user may not write are read-only here, matching the column grants,
 * and only non-null and changed columns are sent, so database defaults apply on insert.
 */
@Entity
@Table(schema = "northstar", name = "tenants")
@DynamicInsert
@DynamicUpdate
public class Tenant implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String slug;

    private String industry;
    private String timezone;
    private String locale;
    private String brandTone;
    private String legalName;
    private String gstin;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String stateCode;

    @JdbcTypeCode(SqlTypes.JSON)
    private String billingAddress;

    private Boolean approvalMode;

    @JdbcTypeCode(SqlTypes.JSON)
    private String quietHours;

    // Owned by billing and Northstar admin; app_user has no privilege on these columns.
    @Generated
    @Column(insertable = false, updatable = false)
    private String status;

    @Column(insertable = false, updatable = false)
    private UUID planId;

    @Column(insertable = false, updatable = false)
    private Instant activatedAt;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected Tenant() {}

    /** A new business. Its id must equal app.tenant_id in the creating transaction (step 1.7). */
    public static Tenant create(UUID id, String name, String slug) {
        Tenant tenant = new Tenant();
        tenant.id = id;
        tenant.name = name;
        tenant.slug = slug;
        tenant.isNew = true;
        return tenant;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public String getTimezone() {
        return timezone;
    }

    public Boolean getApprovalMode() {
        return approvalMode;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
