package com.northstar.workflow;

import java.time.Duration;
import java.util.Objects;

/**
 * What one attempt at an action came to. Error codes are short fixed codes (lowercase letters, digits, underscores),
 * such as "http_503" or "timeout": never message text, tokens, phone numbers or payment details.
 */
public sealed interface ActionOutcome {

    /** The side effect happened. The result is JSON of IDs and status codes (e.g. a provider message ID), or null. */
    record Succeeded(String resultJson) implements ActionOutcome {}

    /** The attempt failed. retryAfter is a provider's Retry-After, if it sent one; otherwise null. */
    record Failed(FailureClass failureClass, String code, Duration retryAfter) implements ActionOutcome {
        public Failed {
            Objects.requireNonNull(failureClass, "failureClass");
            if (code == null || !code.matches("[a-z0-9_]{1,64}")) {
                throw new IllegalArgumentException("Error codes are fixed codes only");
            }
            if (retryAfter != null && retryAfter.isNegative()) throw new IllegalArgumentException("retryAfter < 0");
        }
    }

    static ActionOutcome succeeded(String resultJson) {
        return new Succeeded(resultJson);
    }

    static ActionOutcome retryable(String code) {
        return new Failed(FailureClass.RETRYABLE, code, null);
    }

    static ActionOutcome retryableAfter(String code, Duration retryAfter) {
        return new Failed(FailureClass.RETRYABLE, code, retryAfter);
    }

    static ActionOutcome permanent(String code) {
        return new Failed(FailureClass.PERMANENT, code, null);
    }

    static ActionOutcome auth(String code) {
        return new Failed(FailureClass.AUTH, code, null);
    }
}
