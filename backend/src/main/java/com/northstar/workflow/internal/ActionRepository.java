package com.northstar.workflow.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
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
     * SQL for on conflict; the tenant is bound explicitly from the scope, and RLS checks it again.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into northstar.actions
              (id, tenant_id, lead_id, type, status, idempotency_key, mode, payload, next_attempt_at, stage_entry_id)
            values (:id, :tenantId, :leadId, :type, :status, :idempotencyKey, :mode, cast(:payload as jsonb),
                    :nextAttemptAt, :stageEntryId)
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
            @Param("stageEntryId") UUID stageEntryId);

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

    List<Action> findByStatusAndType(String status, String type);
}
