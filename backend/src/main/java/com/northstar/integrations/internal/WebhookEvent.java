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
 * A webhook routed to its tenant (northstar.webhook_events, data-model.md section 9). A lifecycle row: only
 * processedAt and error change after the insert, matching the column grants, and it is never deleted. Dedupe on
 * (provider, providerEventId) happens at insert, with on conflict do nothing (step 2.2), so a duplicate never becomes an
 * entity. error is a fixed code, never provider text.
 */
@Entity
@Table(schema = "northstar", name = "webhook_events")
@DynamicInsert
@DynamicUpdate
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(nullable = false, updatable = false)
    private String providerEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant receivedAt;

    @Column(insertable = false)
    private Instant processedAt;

    @Column(insertable = false)
    private String error;

    protected WebhookEvent() {}

    WebhookEvent(String provider, String providerEventId, String payload) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.payload = payload;
    }

    void markProcessed(Instant at) {
        this.processedAt = at;
    }

    void recordError(String errorCode) {
        this.error = errorCode;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getProviderEventId() {
        return providerEventId;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }

    Instant getProcessedAt() {
        return processedAt;
    }

    String getError() {
        return error;
    }
}
