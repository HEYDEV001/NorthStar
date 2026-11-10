package com.northstar.workflow;

/**
 * How a failed attempt is treated (integration-specs.md section 1, workflow-spec.md section 10). Stored as
 * actions.last_error_class.
 */
public enum FailureClass {
    /** Timeouts, 429s and 5xx: tried again on the type's schedule until its attempts run out. */
    RETRYABLE("retryable"),
    /** Invalid input, revoked authorisation: failed at once, with an alert. */
    PERMANENT("permanent"),
    /** Expired credentials: the action pauses without using an attempt and resumes when the integration reconnects. */
    AUTH("auth");

    private final String databaseValue;

    FailureClass(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static FailureClass fromDatabaseValue(String value) {
        for (FailureClass failure : values()) {
            if (failure.databaseValue.equals(value)) return failure;
        }
        throw new IllegalArgumentException("Unknown failure class");
    }
}
