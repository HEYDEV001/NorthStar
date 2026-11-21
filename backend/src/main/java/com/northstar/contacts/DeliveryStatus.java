package com.northstar.contacts;

import java.util.Optional;

/**
 * A delivery status Meta reports for an outbound message (step 2.7, integration-specs.md section 2): the ones Northstar
 * tracks. Meta's {@code played} (voice messages, which Northstar does not send) and anything else have no value here.
 */
public enum DeliveryStatus {
    SENT("sent"),
    DELIVERED("delivered"),
    READ("read"),
    FAILED("failed");

    private final String code;

    DeliveryStatus(String code) {
        this.code = code;
    }

    /** messages.status, and Meta's {@code statuses[].status}. */
    public String code() {
        return code;
    }

    public static Optional<DeliveryStatus> fromCode(String code) {
        for (DeliveryStatus status : values()) {
            if (status.code.equals(code)) return Optional.of(status);
        }
        return Optional.empty();
    }
}
