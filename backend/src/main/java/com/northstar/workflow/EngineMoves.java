package com.northstar.workflow;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The engine's stage moves (step 3.3, workflow-spec.md section 3), for the system actor only, inside the caller's
 * transaction. A lead always runs on its own workflow version (W36): every move is checked against that version's
 * config, never the current one, and the lead's stage must be declared there. A version the engine cannot read (no
 * {@code schema_version}, or one it does not know) is never run: the lead stays, and it is logged once per version.
 *
 * <p>Each move reads the lead, checks it, and changes it in one update that checks the version it read (P7). When
 * another writer changed the lead in between, it reads it again and checks the move again, at most
 * {@value #MAX_ATTEMPTS} times, then throws {@link LeadChangedConcurrentlyException} (retryable). Every change writes a
 * {@code stage_changed} timeline row naming the trigger and its actor.
 *
 * <p>Re-opening a closed lead (an exit out of a closing stage) is refused while the contact has another open lead; if
 * one appears meanwhile, the one-open-lead index fails the update and the caller's transaction, which a retry settles.
 */
public interface EngineMoves {

    int MAX_ATTEMPTS = 3;

    /**
     * Moves the lead along an exit of its current stage to {@code toStage}, only if that exit's {@code on} is the
     * trigger's event. Exit conditions ({@code if}) are the caller's to evaluate (step 3.4).
     */
    Result followExit(UUID leadId, String toStage, Trigger trigger);

    /** Moves an open lead to Needs Human, remembering the stage it came from. A lead already there: unchanged. */
    Result toNeedsHuman(UUID leadId, Trigger trigger, String reason);

    /** Moves a lead in Needs Human back to the stage it entered from. A lead not in Needs Human: unchanged. */
    Result handBack(UUID leadId, Trigger trigger);

    /** Moves an open lead to Lost, recording the reason as its lost_reason. A lead already Lost: unchanged. */
    Result toLost(UUID leadId, Trigger trigger, String reason);

    /**
     * Moves a lead in the stage whose step is {@code score} to Junk, for a filter rule of its version whose
     * {@code then} is {@code junk}, recording the rule as its junk_rule_id. A lead already in Junk: unchanged.
     */
    Result toJunkByFilterRule(UUID leadId, String ruleId, Trigger trigger);

    /** What a move did. */
    enum Result {
        /** The lead moved. */
        MOVED,
        /** The lead was already where the move leads (or, for a hand back, not in Needs Human). */
        UNCHANGED,
        /** The lead's version does not allow the move from where the lead is. */
        NOT_ALLOWED,
        /** The lead's version has no schema_version, or one the engine does not know. */
        VERSION_UNSUPPORTED,
        /** No such lead in the tenant, or a deleted one. */
        NOT_FOUND;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Who caused a move the engine makes: the AI, the system (timers, actions), or the client (a message, a tap). */
    enum Actor {
        AI,
        SYSTEM,
        CONTACT;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The event behind a move: its type (workflow-spec.md section 6), its {@code events} row when it has one (the
     * timeline row references it), and its actor.
     */
    record Trigger(String eventType, UUID eventId, Actor actor) {

        private static final Pattern EVENT_TYPE = Pattern.compile("[a-z]+(\\.[a-z_]+)+");

        public Trigger {
            Objects.requireNonNull(actor, "actor");
            if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
                throw new IllegalArgumentException("eventType must be an event type of workflow-spec.md section 6");
            }
        }
    }

    /** A reason the engine gives: a fixed code, never text (the timeline is insert-only). */
    Pattern REASON = Pattern.compile("[a-z][a-z0-9_]{0,63}");
}
