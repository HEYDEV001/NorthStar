package com.northstar.workflow.internal;

import static com.northstar.workflow.internal.ConfigFormatV1.JUNK;
import static com.northstar.workflow.internal.ConfigFormatV1.LOST;
import static com.northstar.workflow.internal.ConfigFormatV1.NEEDS_HUMAN;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineMoves;
import com.northstar.workflow.LeadChangedConcurrentlyException;
import com.northstar.workflow.internal.StageChanges.Applied;
import com.northstar.workflow.internal.StageChanges.Cause;
import com.northstar.workflow.internal.StageChanges.LeadRow;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link EngineMoves} (step 3.3, workflow-spec.md section 3), for the system actor. MANDATORY: every call joins the
 * caller's transaction (READ COMMITTED), so reading the lead again after a failed update sees what the other writer
 * committed, and the move is checked again against it.
 *
 * <p>Logs carry IDs, stage keys and codes only.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class EngineMovesService implements EngineMoves {

    private static final Logger log = LoggerFactory.getLogger(EngineMovesService.class);

    static final String LEAD_STAGE_NOT_IN_VERSION = "lead_stage_not_in_version";
    static final String STAGE_UNKNOWN = "stage_unknown";
    static final String LEAD_CLOSED = "lead_closed";
    static final String NO_SUCH_EXIT = "no_such_exit";
    static final String NOT_SCORE_STAGE = "not_score_stage";
    static final String FILTER_RULE_UNKNOWN = "filter_rule_unknown";
    static final String CONTACT_HAS_OPEN_LEAD = "contact_has_open_lead";

    private final StageChanges changes;
    private final WorkflowGraphs graphs;

    EngineMovesService(StageChanges changes, WorkflowGraphs graphs) {
        this.changes = changes;
        this.graphs = graphs;
    }

    /** What a move means for a lead as read: nothing to do, a refusal, or a change to make. */
    private sealed interface Plan {}

    private record Unchanged() implements Plan {}

    private record NotAllowed(String code) implements Plan {}

    private record Change(String to, MoveKind kind) implements Plan {}

    @FunctionalInterface
    private interface Planner {
        Plan plan(WorkflowGraph graph, LeadRow lead);
    }

    @Override
    public Result followExit(UUID leadId, String toStage, Trigger trigger) {
        Objects.requireNonNull(toStage, "toStage");
        return run(leadId, trigger, null, null, (graph, lead) -> {
            if (!graph.declares(lead.stage())) return new NotAllowed(LEAD_STAGE_NOT_IN_VERSION);
            // Format v1 refuses an exit to its own stage; a version from before it could still hold one.
            if (toStage.equals(lead.stage())) return new NotAllowed(NO_SUCH_EXIT);
            boolean exit = graph.exits(lead.stage()).stream()
                    .anyMatch(e -> e.to().equals(toStage) && e.on().equals(trigger.eventType()));
            if (!exit || !graph.declares(toStage)) return new NotAllowed(NO_SUCH_EXIT);
            return new Change(toStage, MoveKind.EXIT);
        });
    }

    @Override
    public Result toNeedsHuman(UUID leadId, Trigger trigger, String reason) {
        requireReason(reason);
        return run(leadId, trigger, reason, null, (graph, lead) -> {
            if (NEEDS_HUMAN.equals(lead.stage())) return new Unchanged();
            return openStageTo(graph, lead, NEEDS_HUMAN, MoveKind.NEEDS_HUMAN);
        });
    }

    @Override
    public Result handBack(UUID leadId, Trigger trigger) {
        return run(leadId, trigger, null, null, (graph, lead) -> {
            if (!NEEDS_HUMAN.equals(lead.stage())) return new Unchanged();
            if (!graph.declares(lead.stage())) return new NotAllowed(LEAD_STAGE_NOT_IN_VERSION);
            // A lead migrated into Needs Human (step 3.11) may come from a stage this version does not declare.
            if (!graph.declares(lead.previousStage())) return new NotAllowed(STAGE_UNKNOWN);
            return new Change(lead.previousStage(), MoveKind.HAND_BACK);
        });
    }

    @Override
    public Result toLost(UUID leadId, Trigger trigger, String reason) {
        requireReason(reason);
        return run(leadId, trigger, reason, null, (graph, lead) -> {
            if (LOST.equals(lead.stage())) return new Unchanged();
            return openStageTo(graph, lead, LOST, MoveKind.LOST);
        });
    }

    @Override
    public Result toJunkByFilterRule(UUID leadId, String ruleId, Trigger trigger) {
        Objects.requireNonNull(ruleId, "ruleId");
        return run(leadId, trigger, null, ruleId, (graph, lead) -> {
            if (JUNK.equals(lead.stage())) return new Unchanged();
            if (!graph.declares(lead.stage())) return new NotAllowed(LEAD_STAGE_NOT_IN_VERSION);
            if (!graph.isScoreStage(lead.stage())) return new NotAllowed(NOT_SCORE_STAGE);
            if (!graph.isJunkRule(ruleId) || !graph.declares(JUNK)) return new NotAllowed(FILTER_RULE_UNKNOWN);
            return new Change(JUNK, MoveKind.FILTER_RULE);
        });
    }

    /** One of the engine's own moves from any open, declared stage (workflow-spec.md section 3). */
    private static Plan openStageTo(WorkflowGraph graph, LeadRow lead, String to, MoveKind kind) {
        if (!graph.declares(lead.stage())) return new NotAllowed(LEAD_STAGE_NOT_IN_VERSION);
        if (!graph.declares(to)) return new NotAllowed(STAGE_UNKNOWN);
        if (!StageRules.isOpen(lead.stage())) return new NotAllowed(LEAD_CLOSED);
        return new Change(to, kind);
    }

    private Result run(UUID leadId, Trigger trigger, String reason, String ruleId, Planner planner) {
        Objects.requireNonNull(leadId, "leadId");
        Objects.requireNonNull(trigger, "trigger");
        UUID tenant = systemTenant();
        WorkflowGraph graph = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<LeadRow> read = changes.read(tenant, leadId);
            if (read.isEmpty()) {
                log.info("lead.move_refused lead_id={} reason=not_found", leadId);
                return Result.NOT_FOUND;
            }
            LeadRow lead = read.get();
            if (graph == null) {
                // A lead's version never changes (NSL01), so it is read once for every attempt.
                Optional<WorkflowGraph> readable = graphs.forLead(tenant, lead.workflowVersionId(), leadId);
                if (readable.isEmpty()) return Result.VERSION_UNSUPPORTED;
                graph = readable.get();
            }
            Plan plan = planner.plan(graph, lead);
            if (plan instanceof Unchanged) return Result.UNCHANGED;
            if (plan instanceof NotAllowed refusal) return notAllowed(leadId, refusal.code());
            Change change = (Change) plan;
            if (WorkflowGraph.isClosing(lead.stage())
                    && StageRules.isOpen(change.to())
                    && changes.otherOpenLead(tenant, lead.contactId(), leadId)) {
                return notAllowed(leadId, CONTACT_HAS_OPEN_LEAD);
            }
            Optional<Applied> moved = changes.move(tenant, lead, change.to(), change.kind(), reason, ruleId);
            if (moved.isEmpty()) {
                log.info("lead.move_retried lead_id={} attempt={}", leadId, attempt);
                continue;
            }
            Cause cause = trigger.eventId() == null
                    ? new Cause(trigger.eventType(), null, null)
                    : new Cause(trigger.eventType(), "events", trigger.eventId());
            changes.recordChange(
                    tenant,
                    moved.get(),
                    change.kind(),
                    StageChanges.Actor.of(trigger.actor().code()),
                    cause,
                    reason,
                    ruleId);
            log.info(
                    "lead.stage_changed lead_id={} from={} to={} move={} actor={} lead_version={}",
                    leadId,
                    lead.stage(),
                    change.to(),
                    change.kind().code(),
                    trigger.actor().code(),
                    moved.get().after().version());
            return Result.MOVED;
        }
        log.warn("lead.move_gave_up lead_id={} attempts={}", leadId, MAX_ATTEMPTS);
        throw new LeadChangedConcurrentlyException(leadId, MAX_ATTEMPTS);
    }

    private static Result notAllowed(UUID leadId, String code) {
        log.info("lead.move_refused lead_id={} reason={}", leadId, code);
        return Result.NOT_ALLOWED;
    }

    private static void requireReason(String reason) {
        if (reason == null || !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("The engine's reason is a fixed code");
        }
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("The engine moves leads as the system actor");
        return context.tenantId();
    }
}
