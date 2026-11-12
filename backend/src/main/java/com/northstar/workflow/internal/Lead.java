package com.northstar.workflow.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.TenantId;

/**
 * A lead (northstar.leads, data-model.md section 6), mapped only as far as step 1.13 needs: the engine (Phase 3) maps
 * the rest. RLS shows staff only the leads assigned to them (P11). version is the optimistic lock for P7.
 */
@Entity
@Table(schema = "northstar", name = "leads")
@DynamicUpdate
public class Lead {

    @Id
    @Column(updatable = false)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID contactId;

    @Column(nullable = false)
    private UUID workflowVersionId;

    @Column(nullable = false)
    private String stage;

    private UUID assignedMembershipId;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected Lead() {}

    UUID getId() {
        return id;
    }

    UUID getContactId() {
        return contactId;
    }

    String getStage() {
        return stage;
    }

    UUID getAssignedMembershipId() {
        return assignedMembershipId;
    }

    int getVersion() {
        return version;
    }
}
