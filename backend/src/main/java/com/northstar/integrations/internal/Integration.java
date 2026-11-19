package com.northstar.integrations.internal;

import jakarta.persistence.Column;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One connected provider of a business (northstar.integrations, data-model.md section 9). The tenant and provider
 * never change, matching the column grants. lastErrorCode is a fixed code, never provider message text. The credential
 * itself sits in the secrets schema, which this module reads only in the worker (step 2.1b); here it is only an ID.
 */
@Entity
@Table(schema = "northstar", name = "integrations")
@DynamicInsert
@DynamicUpdate
public class Integration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(nullable = false)
    private String status;

    private String accountLabel;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] scopes = new String[0];

    private UUID credentialId;
    private Instant lastSuccessAt;
    private String lastErrorCode;
    private Instant lastErrorAt;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Integration() {}

    Integration(String provider, String status, UUID credentialId) {
        this.provider = provider;
        this.status = status;
        this.credentialId = credentialId;
    }

    /** A failed call: the status becomes error, with a fixed code and when it happened. */
    void recordError(String errorCode, Instant at) {
        this.status = "error";
        this.lastErrorCode = errorCode;
        this.lastErrorAt = at;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getProvider() {
        return provider;
    }

    String getStatus() {
        return status;
    }

    UUID getCredentialId() {
        return credentialId;
    }

    String getLastErrorCode() {
        return lastErrorCode;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
