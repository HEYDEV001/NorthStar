package com.northstar.workflow.internal;

import com.northstar.api.ApiException;
import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.api.UnprocessableException;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineEvents;
import com.northstar.workflow.LeadMoves;
import com.northstar.workflow.MoveReason;
import com.northstar.workflow.StaleLeadException;
import com.northstar.workflow.internal.StageChanges.Actor;
import com.northstar.workflow.internal.StageChanges.Applied;
import com.northstar.workflow.internal.StageChanges.Cause;
import com.northstar.workflow.internal.StageChanges.LeadRow;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LeadMoves} (step 3.3, workflow-spec.md section 3). MANDATORY: every call joins the caller's transaction, and a
 * refusal is an API exception that rolls it back. In order: a staff member's forced move is 403 before anything is
 * read; the lead as the caller sees it (404 otherwise, P9, P11); its own version, which must be one the engine runs;
 * the version the move was made against (P7); the target stage; the move itself (StageRules, or an admin's force);
 * then the change, its {@code staff.action} event, its timeline row and, for a forced move, the audit entry.
 *
 * <p>Logs carry IDs, stage keys and codes only.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class LeadMovesService implements LeadMoves {

    private static final Logger log = LoggerFactory.getLogger(LeadMovesService.class);

    static final String STAFF_ACTION = "staff.action";

    private final StageChanges changes;
    private final WorkflowGraphs graphs;
    private final EngineEvents events;
    private final TenantDirectory tenants;
    private final AuditTrail audit;

    LeadMovesService(
            StageChanges changes,
            WorkflowGraphs graphs,
            EngineEvents events,
            TenantDirectory tenants,
            AuditTrail audit) {
        this.changes = changes;
        this.graphs = graphs;
        this.events = events;
        this.tenants = tenants;
        this.audit = audit;
    }

    @Override
    public Moved move(
            UUID leadId, String toStage, int expectedVersion, MoveReason reason, boolean force, UUID timelineEventId) {
        Objects.requireNonNull(leadId, "leadId");
        Objects.requireNonNull(timelineEventId, "timelineEventId");
        Objects.requireNonNull(toStage, "toStage");
        Objects.requireNonNull(reason, "reason");
        TenantContext context = member();
        if (force && context.role() != Role.ADMIN) {
            throw refused(leadId, new ForbiddenException("admin_only", "Only an admin may force a stage move"));
        }
        UUID me = tenants.currentMembershipId()
                .orElseThrow(() -> new ForbiddenException("no_active_membership", "This account has no membership"));
        UUID tenant = context.tenantId();

        LeadRow lead =
                changes.read(tenant, leadId).orElseThrow(() -> refused(leadId, new NotFoundException("lead", leadId)));
        WorkflowGraph graph = graphs.forLead(tenant, lead.workflowVersionId(), leadId)
                .orElseThrow(() -> refused(
                        leadId,
                        new ConflictException(
                                "workflow_version_unsupported",
                                "This lead's workflow version cannot be run; it waits for migration")));
        if (lead.version() != expectedVersion) throw refused(leadId, new StaleLeadException(lead.view()));
        if (!graph.declares(toStage)) {
            throw refused(
                    leadId,
                    new UnprocessableException("stage_unknown", "The lead's workflow version has no such stage"));
        }
        if (toStage.equals(lead.stage())) {
            throw refused(leadId, new ConflictException("stage_unchanged", "The lead is already in that stage"));
        }

        MoveKind kind = switch (StageRules.forMember(graph, lead.stage(), lead.previousStage(), toStage)) {
            case StageRules.Allowed allowed -> allowed.kind();
            case StageRules.Refused refusal -> {
                if (!force) throw refused(leadId, new ConflictException(refusal.code(), message(refusal)));
                yield MoveKind.FORCED;
            }
        };

        if (WorkflowGraph.isClosing(lead.stage())
                && StageRules.isOpen(toStage)
                && changes.otherOpenLead(tenant, lead.contactId(), leadId)) {
            throw refused(leadId, contactHasOpenLead());
        }
        Optional<Applied> moved;
        try {
            moved = changes.move(tenant, lead, toStage, kind, reason.code(), null);
        } catch (DuplicateKeyException openLeadMeanwhile) {
            // The one-open-lead index: another open lead the caller cannot see, or one opened meanwhile.
            throw refused(leadId, contactHasOpenLead());
        }
        if (moved.isEmpty()) {
            // Changed (or deleted) between the read and the update: refused with the lead as it now is.
            LeadRow now = changes.read(tenant, leadId)
                    .orElseThrow(() -> refused(leadId, new NotFoundException("lead", leadId)));
            throw refused(leadId, new StaleLeadException(now.view()));
        }
        Applied change = moved.get();

        UUID eventId = events.recordStageMove(new EngineEvents.StageMove(
                leadId,
                lead.stage(),
                toStage,
                kind.code(),
                reason.code(),
                kind == MoveKind.FORCED,
                me,
                change.after().version(),
                change.after().stageEnteredAt()));
        UUID rowId = changes.recordChange(
                timelineEventId,
                tenant,
                change,
                kind,
                Actor.staff(me),
                new Cause(STAFF_ACTION, "events", eventId),
                reason.code(),
                null);
        if (kind == MoveKind.FORCED) audit.recordChange(STAGE_FORCED, "leads", leadId, lead.stage(), toStage);

        log.info(
                "lead.stage_changed lead_id={} from={} to={} move={} actor=staff membership_id={} lead_version={}",
                leadId,
                lead.stage(),
                toStage,
                kind.code(),
                me,
                change.after().version());
        return new Moved(change.after().view(), rowId, kind.code());
    }

    @Override
    public Optional<LeadStage> stage(UUID leadId) {
        Objects.requireNonNull(leadId, "leadId");
        return changes.read(member().tenantId(), leadId).map(LeadRow::view);
    }

    private static ConflictException contactHasOpenLead() {
        return new ConflictException(
                "contact_has_open_lead", "The lead's contact has another open lead, so this one cannot be reopened");
    }

    private static String message(StageRules.Refused refusal) {
        return StageRules.TRANSITION_ENGINE_ONLY.equals(refusal.code())
                ? "Only a verified payment or refund event moves the lead along this exit"
                : "The lead's workflow version does not allow this move";
    }

    /** Logs a refusal by its code, and returns it to throw. */
    private static <E extends RuntimeException> E refused(UUID leadId, E refusal) {
        String code = refusal instanceof ApiException api ? api.code() : "unknown";
        log.info("lead.move_refused lead_id={} reason={}", leadId, code);
        return refusal;
    }

    private static TenantContext member() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Leads are moved by hand by admins and staff only");
        }
        return context;
    }
}
