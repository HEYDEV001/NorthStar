package com.northstar.workflow;

/**
 * How a failed attempt is treated (integration-specs.md section 1, workflow-spec.md section 10). Stored as
 * actions.last_error_class.
 */
public enum FailureClass {
    /** Timeouts, 429s and 5xx: tried again on the type's schedule until its attempts run out. */
    RETRYABLE("retryable"),
    /** Invalid input, or a request the provider will never accept: failed at once, with an alert. */
    PERMANENT("permanent"),
    /**
     * A business's credentials expired, invalid or revoked, or a permission removed (decided 21 Nov 2026): the action
     * pauses without using an attempt and resumes when the integration reconnects ({@link Actions#resumePaused}).
     */
    AUTH("auth"),
    /**
     * Something the action depends on is unavailable for now, such as a WhatsApp template Meta has paused (step 2.8c,
     * W34): the action pauses without using an attempt and without the auth alert, and resumes when that dependency is
     * back ({@link DependentActions#unblock}). A reconnect never resumes it; unblocking never resumes an auth pause.
     */
    BLOCKED("blocked");

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
