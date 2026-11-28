package com.northstar.workflow.internal;

import java.util.Locale;

/**
 * The kinds of stage change (step 3.3, workflow-spec.md section 3), each a {@code stage_changed} timeline row's
 * summary: a lead's creation, an exit of its stage, a filter rule, the engine's own moves to and from Needs Human and to
 * Lost, a Junk lead restored, and an admin's forced move.
 */
enum MoveKind {
    CREATED,
    EXIT,
    FILTER_RULE,
    NEEDS_HUMAN,
    HAND_BACK,
    LOST,
    RESTORE,
    FORCED;

    String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
