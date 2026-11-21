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
import org.hibernate.annotations.TenantId;

/**
 * A business's WhatsApp number (northstar.whatsapp_accounts, data-model.md section 9). phoneNumberId routes inbound
 * webhooks to the tenant and is unique across tenants; it and wabaId never change, matching the column grants. A WABA
 * belongs to one tenant (step 2.8a). qualityRating and messagingLimit hold {@link QualityRating} and
 * {@link MessagingLimit} values (step 2.8a); the sync writes them through {@link WhatsAppAccountHealth}.
 */
@Entity
@Table(schema = "northstar", name = "whatsapp_accounts")
@DynamicInsert
@DynamicUpdate
public class WhatsAppAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String wabaId;

    @Column(nullable = false, updatable = false)
    private String phoneNumberId;

    private String displayPhone;
    private String displayName;
    private String qualityRating;
    private String messagingLimit;
    private Instant verifiedAt;

    @Generated
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected WhatsAppAccount() {}

    WhatsAppAccount(String wabaId, String phoneNumberId) {
        this.wabaId = wabaId;
        this.phoneNumberId = phoneNumberId;
    }

    /** What Meta reports about the number's quality and its portfolio's messaging limit (step 2.8a). */
    void recordQuality(QualityRating qualityRating, MessagingLimit messagingLimit) {
        this.qualityRating = qualityRating.databaseValue();
        this.messagingLimit = messagingLimit.databaseValue();
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getWabaId() {
        return wabaId;
    }

    String getPhoneNumberId() {
        return phoneNumberId;
    }

    String getQualityRating() {
        return qualityRating;
    }
}
