package com.northstar.workflow.internal;

import java.util.Locale;

/** The fixed codes of a workflow config's problems (workflow-spec.md section 15, format v1). */
enum ProblemCode {
    // A draft with one of these four cannot be stored at all.
    JSON_INVALID,
    JSON_MEMBER_DUPLICATE,
    CONFIG_TOO_LARGE,
    CONFIG_NOT_OBJECT,
    // These are warnings on a draft and refuse a publish.
    SCHEMA_VERSION_UNSUPPORTED,
    FIELD_REQUIRED,
    FIELD_UNKNOWN,
    FIELD_SERVER_SET,
    TYPE_INVALID,
    VALUE_INVALID,
    STAGE_NEW_MISSING,
    STAGE_NEEDS_HUMAN_MISSING,
    STAGE_KEY_DUPLICATE,
    STAGE_UNKNOWN,
    STAGE_UNREACHABLE,
    STEP_UNKNOWN,
    EVENT_UNKNOWN,
    EVENT_NOT_ALLOWED,
    ACTION_UNKNOWN,
    TEMPLATE_UNKNOWN,
    TIMER_TYPE_UNKNOWN,
    TIMER_DUPLICATE,
    CONDITION_OPERATOR_UNKNOWN,
    CONDITION_FIELD_UNKNOWN,
    CONDITION_INVALID,
    FILTER_RULE_ID_DUPLICATE,
    // After the 500th problem.
    TOO_MANY_PROBLEMS;

    /** The code as reported: the constant's name in lowercase, such as {@code stage_new_missing}. */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
