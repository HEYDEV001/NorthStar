package com.northstar.workflow.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ActionRepository extends JpaRepository<Action, UUID> {

    /**
     * Inserts the action unless the tenant already has one with this idempotency key; 1 if inserted, 0 if not. Native
     * SQL for on conflict; the tenant is bound explicitly from the scope, and RLS checks it again. An action for a
     * contact who has opted out is inserted as cancelled with its reason (step 2.5).
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into northstar.actions
              (id, tenant_id, lead_id, type, status, idempotency_key, mode, payload, next_attempt_at, stage_entry_id,
               recipient_contact_id, cancel_reason)
            values (:id, :tenantId, :leadId, :type, :status, :idempotencyKey, :mode, cast(:payload as jsonb),
                    :nextAttemptAt, :stageEntryId, :recipientContactId, :cancelReason)
            on conflict (tenant_id, idempotency_key) do nothing""")
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("leadId") UUID leadId,
            @Param("type") String type,
            @Param("status") String status,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("mode") String mode,
            @Param("payload") String payload,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("stageEntryId") UUID stageEntryId,
            @Param("recipientContactId") UUID recipientContactId,
            @Param("cancelReason") String cancelReason);

    /** An action's recipient, with the type and payload that say what it sends (steps 2.5 and 2.9b). */
    interface Recipient {
        UUID getRecipientContactId();

        String getType();

        String getPayload();
    }

    /**
     * The action's recipient, type and payload (steps 2.5 and 2.9b), read as plain values without loading the action
     * into the persistence context, so the claim can read the recipient's consent before it locks the action. Empty
     * when the action has no recipient or is not in the tenant. All three are written once, so reading them unlocked is
     * safe.
     */
    @Query(nativeQuery = true, value = """
            select recipient_contact_id as recipientContactId, type, payload::text as payload
            from northstar.actions
            where tenant_id = :tenantId and id = :id and recipient_contact_id is not null""")
    Optional<Recipient> findRecipient(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    Optional<Action> findByIdempotencyKey(String idempotencyKey);

    /** Loads an action and locks its row (select ... for update): claiming and recording an attempt both use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Action> findLockedById(UUID id);

    /** The current tenant's actions due to run: pending and due, or processing with an expired lease. */
    @Query("""
            select a from Action a
            where (a.status = 'pending' and a.nextAttemptAt <= :now)
               or (a.status = 'processing' and a.leaseExpiresAt <= :now)""")
    List<Action> findDue(@Param("now") Instant now);

    List<Action> findByStatusOrderByLastAttemptAtDesc(String status);

    /** This type's actions in this status whose last error has this class: a reconnect resumes auth pauses only. */
    List<Action> findByStatusAndTypeAndLastErrorClass(String status, String type, String lastErrorClass);

    /**
     * The tenant's actions of this type, in these statuses, whose payload's field holds one of the values (step 2.8c,
     * {@link com.northstar.workflow.DependentActions}), locked in ID order (select ... for update). An action that
     * changes status while this waits for its lock is re-checked once the lock is granted, so one just claimed
     * (processing) is left out. Native SQL for the payload field; the tenant is bound explicitly, and RLS checks it
     * again. Values must not be empty.
     */
    @Query(nativeQuery = true, value = """
            select * from northstar.actions
            where tenant_id = :tenantId and type = :type and status in (:statuses)
              and payload ->> cast(:field as text) in (:values)
            order by id
            for update""")
    List<Action> findDependentsLocked(
            @Param("tenantId") UUID tenantId,
            @Param("type") String type,
            @Param("statuses") Collection<String> statuses,
            @Param("field") String field,
            @Param("values") Collection<String> values);

    /** As {@link #findDependentsLocked}, for the ones paused as blocked with this code. Values must not be empty. */
    @Query(nativeQuery = true, value = """
            select * from northstar.actions
            where tenant_id = :tenantId and type = :type and status = 'paused'
              and last_error_class = 'blocked' and last_error_code = :code
              and payload ->> cast(:field as text) in (:values)
            order by id
            for update""")
    List<Action> findBlockedDependentsLocked(
            @Param("tenantId") UUID tenantId,
            @Param("type") String type,
            @Param("code") String code,
            @Param("field") String field,
            @Param("values") Collection<String> values);
}
