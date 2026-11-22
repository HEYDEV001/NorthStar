package com.northstar.workflow.internal;

import com.northstar.workflow.ActionView;
import com.northstar.workflow.FailureClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One action (northstar.actions, data-model.md section 6). Rows are inserted by {@link ActionRepository#insertIfAbsent}
 * so a repeated idempotency key creates nothing; afterwards only the lifecycle columns change, matching the column
 * grants, and rows are never deleted.
 */
@Entity
@Table(schema = "northstar", name = "actions")
@DynamicUpdate
public class Action {

    static final String PENDING = "pending";
    static final String PROCESSING = "processing";
    static final String SUCCEEDED = "succeeded";
    static final String FAILED = "failed";
    static final String NEEDS_REVIEW = "needs_review";
    static final String PAUSED = "paused";
    static final String CANCELLED = "cancelled";

    @Id
    @Column(updatable = false)
    private UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;

    @Column(updatable = false)
    private UUID leadId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    private String result;

    @Column(nullable = false)
    private int attempts;

    private Instant nextAttemptAt;
    private Instant leaseExpiresAt;
    private Instant lastAttemptAt;
    private String lastErrorClass;
    private String lastErrorCode;
    private UUID approverMembershipId;
    private Instant approvedAt;

    @Column(updatable = false)
    private UUID stageEntryId;

    /** The contact the action sends something to (step 2.5); null for an action that messages nobody outside. */
    @Column(updatable = false)
    private UUID recipientContactId;

    /** A fixed code, set exactly when the action is cancelled (step 2.5). */
    private String cancelReason;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    protected Action() {}

    /** Takes the action for one attempt: processing until the lease runs out. */
    void claim(Instant now, Instant leaseExpiresAt) {
        status = PROCESSING;
        attempts++;
        lastAttemptAt = now;
        nextAttemptAt = null;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    void succeed(String resultJson) {
        status = SUCCEEDED;
        result = resultJson;
        leaseExpiresAt = null;
        nextAttemptAt = null;
        lastErrorClass = null;
        lastErrorCode = null;
    }

    void retryAt(Instant next, String errorClass, String code) {
        status = PENDING;
        nextAttemptAt = next;
        leaseExpiresAt = null;
        lastErrorClass = errorClass;
        lastErrorCode = code;
    }

    void fail(String errorClass, String code) {
        status = FAILED;
        nextAttemptAt = null;
        leaseExpiresAt = null;
        lastErrorClass = errorClass;
        lastErrorCode = code;
    }

    /**
     * An attempt that failed as auth or blocked pauses the action and gives the attempt back: it doesn't count toward
     * the retry budget.
     */
    void pause(FailureClass failureClass, String code) {
        if (failureClass != FailureClass.AUTH && failureClass != FailureClass.BLOCKED) {
            throw new IllegalArgumentException("Only auth and blocked failures pause an action");
        }
        status = PAUSED;
        attempts--;
        nextAttemptAt = null;
        leaseExpiresAt = null;
        lastErrorClass = failureClass.databaseValue();
        lastErrorCode = code;
    }

    /**
     * Blocked while waiting to run (step 2.8c): paused as blocked with this code, keeping its attempts and the time it
     * was due, so unblocking it keeps its schedule.
     */
    void block(String code) {
        status = PAUSED;
        lastErrorClass = FailureClass.BLOCKED.databaseValue();
        lastErrorCode = code;
    }

    /**
     * What it was blocked on is back (step 2.8c): pending again at the later of the time it was due and now (now for
     * one blocked during an attempt, which kept no time). Its error stays as the record of why it waited.
     */
    void unblock(Instant now) {
        status = PENDING;
        nextAttemptAt = nextAttemptAt == null || nextAttemptAt.isBefore(now) ? now : nextAttemptAt;
    }

    void approve(UUID approverMembershipId, Instant now) {
        status = PENDING;
        this.approverMembershipId = approverMembershipId;
        approvedAt = now;
        nextAttemptAt = now;
    }

    void resume(Instant now) {
        status = PENDING;
        nextAttemptAt = now;
    }

    /** It no longer applies (step 2.5: its recipient opted out). It never runs, and nothing changes it afterwards. */
    void cancel(String reason) {
        status = CANCELLED;
        cancelReason = reason;
        nextAttemptAt = null;
        leaseExpiresAt = null;
    }

    /** When the action first became runnable: its approval, or its creation. The retry window counts from here. */
    Instant runnableSince() {
        return approvedAt != null ? approvedAt : createdAt;
    }

    ActionView view() {
        return new ActionView(
                id,
                leadId,
                type,
                status,
                mode,
                attempts,
                nextAttemptAt,
                lastAttemptAt,
                lastErrorClass,
                lastErrorCode,
                approverMembershipId,
                approvedAt,
                createdAt,
                recipientContactId,
                cancelReason);
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getType() {
        return type;
    }

    String getStatus() {
        return status;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getPayload() {
        return payload;
    }

    int getAttempts() {
        return attempts;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }
}
