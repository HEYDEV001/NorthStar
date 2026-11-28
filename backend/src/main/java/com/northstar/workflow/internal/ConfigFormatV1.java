package com.northstar.workflow.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Format v1 of a workflow config, as tables (workflow-spec.md sections 5, 7 and 15; decided 27 Nov 2026, step 3.1).
 * {@link ConfigValidator} applies them; the editor's JSON Schema is to be generated from them, never written by hand.
 * Nothing here is a business's setting: no stage of a template, no question, timer value, price or message.
 */
final class ConfigFormatV1 {

    static final int SCHEMA_VERSION = 1;

    /** Stage keys, filter rule IDs and requirement keys: 1–50 lowercase letters, digits and _, starting with a letter. */
    static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,49}");

    /** 24-hour clock time, as quiet hours are written. */
    static final Pattern CLOCK = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

    /** The industry templates (workflow-spec.md section 14). */
    static final Set<String> TEMPLATES = Set.of("shoot_production", "agency", "ac_home_service", "coaching", "blank");

    /** The stage every lead starts in (step 2.4b). */
    static final String NEW = "new";

    static final String NEEDS_HUMAN = "needs_human";
    static final String JUNK = "junk";
    static final String LOST = "lost";
    static final String CANCELLED = "cancelled";
    static final String COMPLETED = "completed";

    /**
     * The stages that close a lead (workflow-spec.md section 3; Cancelled since step 3.3): entering one sets closed_at,
     * so the contact may start a new lead (W2). The database holds the same list (leads_closed_in_closing_stage).
     */
    static final Set<String> CLOSING_STAGES = Set.of(JUNK, LOST, COMPLETED, CANCELLED);

    /** The reserved stages, with the names used when a config gives none (a business may rename them). */
    static final Map<String, String> RESERVED_STAGES = Map.of(
            NEEDS_HUMAN,
            "Needs Human",
            "negotiating",
            "Negotiating",
            JUNK,
            "Junk",
            "lost",
            "Lost",
            "cancelled",
            "Cancelled",
            "completed",
            "Completed");

    /** Where the engine itself moves a lead from any open stage (workflow-spec.md section 3), so they need no exit. */
    static final List<String> ENGINE_TARGETS = List.of(NEEDS_HUMAN, "lost", "cancelled");

    /** The events an exit may wait for: section 6's, but an opt-out, which never changes stage (section 3). */
    static final Set<String> EVENTS = Set.of(
            "message.received",
            "message.status",
            "button.clicked",
            "ai.fields_extracted",
            "ai.escalation",
            "payment.captured",
            "payment.link_expired",
            "payment.failed",
            "refund.processed",
            "timer.fired",
            "staff.action",
            "calendar.changed",
            "sheet.edited",
            "integration.disconnected");

    static final String OPT_OUT_EVENT = "optout.received";

    static final Set<String> TIMER_TYPES = Set.of("follow_up");

    static final String QUALIFY = "qualify";
    static final String SCORE = "score";
    static final String PAYMENT = "payment";
    static final String ADVANCE = "advance";

    /** Section 7's lead fields, plus time (section 15's required_fields). */
    static final Set<String> LEAD_FIELDS =
            Set.of("service", "budget", "date", "time", "location", "source", "score", "band");

    static final Set<String> CONTACT_FIELDS = Set.of("is_repeat_customer", "opt_out");

    /** Fields whose values are integers: budget in paise, like every amount, and the score. */
    static final Set<String> INTEGER_FIELDS = Set.of("budget", "score");

    static final Set<String> BOOLEAN_FIELDS = CONTACT_FIELDS;

    static final String REQUIREMENTS = "requirements.";

    /** What a parameter holds. */
    enum Kind {
        /** An integer from min to max. */
        INTEGER,
        BOOLEAN,
        /** A text of min to max characters, not blank. */
        TEXT,
        /** One of values. */
        CHOICE,
        /** A list of texts of min to max characters, none blank. */
        TEXTS,
        /** A list of distinct lead fields (section 7). */
        LEAD_FIELDS,
        /** {"hot", "warm"}: both required, 0–100, hot above warm. */
        BAND_THRESHOLDS
    }

    record Param(Kind kind, int min, int max, Set<String> values) {

        static Param integer(int min) {
            return new Param(Kind.INTEGER, min, Integer.MAX_VALUE, Set.of());
        }

        static Param integer(int min, int max) {
            return new Param(Kind.INTEGER, min, max, Set.of());
        }

        static Param bool() {
            return new Param(Kind.BOOLEAN, 0, 0, Set.of());
        }

        static Param text(int max) {
            return new Param(Kind.TEXT, 1, max, Set.of());
        }

        static Param choice(String... values) {
            return new Param(Kind.CHOICE, 0, 0, Set.of(values));
        }

        static Param of(Kind kind) {
            return new Param(kind, 0, 0, Set.of());
        }
    }

    /**
     * The step types and their parameters (section 5's key parameters, the complete list for v1). Parameters whose shape
     * section 5 leaves open are not here, so they are refused until the step building their executor defines them.
     */
    static final Map<String, Map<String, Param>> STEPS = steps();

    private static Map<String, Map<String, Param>> steps() {
        Map<String, Map<String, Param>> steps = new LinkedHashMap<>();
        steps.put(
                QUALIFY,
                Map.of(
                        "questions", new Param(Kind.TEXTS, 1, 500, Set.of()),
                        "required_fields", Param.of(Kind.LEAD_FIELDS),
                        "max_turns", Param.integer(1),
                        "language", Param.text(35),
                        "tone", Param.text(200)));
        steps.put(SCORE, Map.of("band_thresholds", Param.of(Kind.BAND_THRESHOLDS), "meta_lead_event", Param.bool()));
        steps.put(
                "call",
                Map.of(
                        "duration_min", Param.integer(1),
                        "staff_skill", Param.text(50),
                        "calendar_required", Param.bool()));
        steps.put(
                "site_visit",
                Map.of(
                        "duration_min", Param.integer(1),
                        "staff_skill", Param.text(50),
                        "location_required", Param.bool()));
        steps.put("quote", Map.of("validity_days", Param.integer(1), "discount_limit_pct", Param.integer(0, 100)));
        steps.put(
                PAYMENT,
                Map.of(
                        "kind", Param.choice(ADVANCE, "full", "balance", "fee"),
                        "advance_pct", Param.integer(1, 100),
                        "link_expiry_hours", Param.integer(1)));
        steps.put("invoice", Map.of("on", Param.choice("payment_captured", "manual")));
        steps.put("schedule", Map.of("duration_min", Param.integer(1), "buffer_min", Param.integer(0)));
        steps.put(
                "assign",
                Map.of(
                        "strategy", Param.choice("least_busy", "round_robin", "skill_match"),
                        "required_skill", Param.text(50)));
        steps.put("job", Map.of("overdue_after_hours", Param.integer(1)));
        steps.put("batch_assign", Map.of("capacity", Param.integer(1)));
        steps.put("delivery", Map.of("requires_approval", Param.bool()));
        steps.put("review_request", Map.of("delay_hours", Param.integer(0)));
        steps.put("close", Map.of("send_purchase_event", Param.bool()));
        return Map.copyOf(steps);
    }

    /** The deepest a condition may nest (section 7). */
    static final int MAX_CONDITION_DEPTH = 10;

    static final int MAX_STAGE_NAME = 60;
    static final int MAX_REASON = 200;

    private ConfigFormatV1() {}

    /** A lead field a step may require (section 7): a lead field or requirements.<key>. */
    static boolean isLeadField(String field) {
        return LEAD_FIELDS.contains(field) || isRequirement(field);
    }

    /** A field a condition may read (section 7). No workspace setting exists yet. */
    static boolean isConditionField(String field) {
        return isLeadField(field) || CONTACT_FIELDS.contains(field);
    }

    private static boolean isRequirement(String field) {
        return field.startsWith(REQUIREMENTS)
                && KEY.matcher(field.substring(REQUIREMENTS.length())).matches();
    }
}
