package com.northstar.workflow.internal;

import static com.northstar.workflow.internal.ConfigFormatV1.JUNK;
import static com.northstar.workflow.internal.ConfigFormatV1.LOST;
import static com.northstar.workflow.internal.ConfigFormatV1.NEEDS_HUMAN;

import java.util.List;
import java.util.Set;

/**
 * Which stage moves a lead's own version allows (step 3.3, workflow-spec.md section 3), as plain decisions with no
 * database: the state machine's table. The engine's moves are each their own method ({@link EngineMovesService}); this
 * is the members' side, which every member move goes through before it is made (or forced).
 */
final class StageRules {

    /** Restoring a Junk lead sends it to Qualifying (workflow-spec.md section 4), when the version declares it. */
    static final String RESTORE_TO = "qualifying";

    /**
     * Exits on these events only move on a verified event, never by a member's hand (decided 28 Nov 2026): a lead is
     * never booked, or its payment taken as failed, expired or refunded, because someone clicked. An admin's forced
     * move stays the audited override.
     */
    static final Set<String> ENGINE_ONLY_EVENTS =
            Set.of("payment.captured", "payment.failed", "payment.link_expired", "refund.processed");

    static final String TRANSITION_NOT_ALLOWED = "transition_not_allowed";
    static final String TRANSITION_ENGINE_ONLY = "transition_engine_only";

    private StageRules() {}

    /** A move allowed as this kind, or refused with this code. */
    sealed interface Decision {}

    record Allowed(MoveKind kind) implements Decision {}

    record Refused(String code) implements Decision {}

    /** Whether a stage is open: every stage but the four closing ones. */
    static boolean isOpen(String stage) {
        return !WorkflowGraph.isClosing(stage);
    }

    /**
     * A member's move from {@code from} (with {@code previousStage}, set only in Needs Human) to {@code to}, not forced.
     * The caller has checked that {@code to} is declared and differs from {@code from}. A lead whose own stage is not
     * declared moves only by force.
     */
    static Decision forMember(WorkflowGraph graph, String from, String previousStage, String to) {
        if (!graph.declares(from)) return new Refused(TRANSITION_NOT_ALLOWED);
        if (NEEDS_HUMAN.equals(from) && to.equals(previousStage)) return new Allowed(MoveKind.HAND_BACK);
        if (NEEDS_HUMAN.equals(to) && isOpen(from)) return new Allowed(MoveKind.NEEDS_HUMAN);
        if (LOST.equals(to) && isOpen(from)) return new Allowed(MoveKind.LOST);
        if (JUNK.equals(from) && RESTORE_TO.equals(to)) return new Allowed(MoveKind.RESTORE);
        List<WorkflowGraph.Exit> exits =
                graph.exits(from).stream().filter(exit -> exit.to().equals(to)).toList();
        if (exits.isEmpty()) return new Refused(TRANSITION_NOT_ALLOWED);
        if (exits.stream().allMatch(exit -> ENGINE_ONLY_EVENTS.contains(exit.on()))) {
            return new Refused(TRANSITION_ENGINE_ONLY);
        }
        return new Allowed(MoveKind.EXIT);
    }
}
