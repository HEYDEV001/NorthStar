package com.northstar.crm.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.api.UnprocessableException;
import com.northstar.crm.internal.LeadResponses.LeadResponse;
import com.northstar.tenancy.IdempotencyKeyHeader;
import com.northstar.tenancy.RequestKeys;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.LeadMoves;
import com.northstar.workflow.MoveReason;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A member's stage move through the API (step 3.3b, workflow-spec.md section 3): {@link LeadMoves} with the request's
 * Idempotency-Key (P21). In order: the reason (400 {@code reason_invalid}); a staff member's {@code force} (403
 * {@code admin_only}, before the key); the key (400 when missing or malformed); then, in one transaction, the key
 * claimed with the move's timeline row as its record, and the move. A repeat of the same request answers with the lead
 * as it is now and moves nothing; the same key with another request, or for a lead the caller can no longer see, is
 * 422 {@code idempotency_key_reused}. A refused move rolls back, keeping no key.
 *
 * <p>Logs carry IDs and codes only, never the key.
 */
@Service
class LeadStageMoves {

    private static final Logger log = LoggerFactory.getLogger(LeadStageMoves.class);

    private final RequestKeys requestKeys;
    private final LeadMoves moves;
    private final LeadViews views;
    private final TransactionTemplate transactions;

    LeadStageMoves(
            RequestKeys requestKeys, LeadMoves moves, LeadViews views, PlatformTransactionManager transactionManager) {
        this.requestKeys = requestKeys;
        this.moves = moves;
        this.views = views;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The lead after the move, and whether this request moved it (false: a repeat). */
    record Done(LeadResponse lead, boolean moved) {}

    Done move(UUID leadId, MoveStageRequest request, String idempotencyKey) {
        Objects.requireNonNull(leadId, "leadId");
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Leads are moved by hand by admins and staff only");
        }
        MoveReason reason = request.moveReason();
        boolean force = request.forced();
        if (force && context.role() != Role.ADMIN) {
            log.info("lead.move_refused lead_id={} reason=admin_only", leadId);
            throw new ForbiddenException("admin_only", "Only an admin may force a stage move");
        }
        String key = IdempotencyKeyHeader.require(idempotencyKey, "A stage move");
        String hash = RequestHash.of(
                RequestKeys.LEAD_MOVE_STAGE,
                leadId.toString(),
                request.to(),
                Integer.toString(request.expectedVersion()),
                reason.code(),
                Boolean.toString(force));

        Done done = transactions.execute(status -> {
            switch (requestKeys.claim(RequestKeys.LEAD_MOVE_STAGE, key, hash, UUID.randomUUID())) {
                case RequestKeys.Reused reused -> throw keyReused(leadId);
                case RequestKeys.Repeat repeat -> {
                    // Its lead may no longer be the caller's to see (reassigned since): the key cannot be used again.
                    return views.lead(leadId).map(lead -> new Done(lead, false)).orElseThrow(() -> keyReused(leadId));
                }
                case RequestKeys.Claimed claimed -> {
                    moves.move(leadId, request.to(), request.expectedVersion(), reason, force, claimed.targetId());
                    LeadResponse lead = views.lead(leadId)
                            .orElseThrow(() -> new IllegalStateException("A lead just moved is visible to its mover"));
                    return new Done(lead, true);
                }
            }
        });
        if (!done.moved()) log.info("lead.move_repeated lead_id={}", leadId);
        return done;
    }

    private static UnprocessableException keyReused(UUID leadId) {
        log.info("lead.move_refused lead_id={} reason=idempotency_key_reused", leadId);
        return new UnprocessableException(
                "idempotency_key_reused", "This Idempotency-Key was already used for a different request");
    }
}
