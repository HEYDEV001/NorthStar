package com.northstar.tenancy.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * One audit entry (northstar.audit_log, data-model.md section 10). Insert-only: @Immutable here, no update or delete
 * privilege for app_user in the database, and the RLS release gate checks both. IDs only, never personal data.
 */
@Entity
@Table(schema = "northstar", name = "audit_log")
@Immutable
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    private UUID actorUserId;

    @Column(nullable = false)
    private String action;

    private String targetTable;
    private UUID targetId;
    private String ipHash;

    // Filled by the database. Not re-read after insert: Hibernate refuses the write lock that would take on an
    // @Immutable entity, and nothing needs it back.
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntry() {}

    public AuditLogEntry(UUID actorUserId, String action, String targetTable, UUID targetId) {
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetTable = targetTable;
        this.targetId = targetId;
    }

    public String getAction() {
        return action;
    }
}
