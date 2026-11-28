package com.northstar.workflow;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Why a member moved a lead (step 3.3, workflow-spec.md section 3; decided 28 Nov 2026). Fixed codes only: the
 * timeline and the audit log are insert-only, so free text written there could never be cleared. An explanation in
 * words belongs in a lead note (step 3.9), which can be.
 */
public enum MoveReason {
    CLIENT_REQUEST,
    CLIENT_DECLINED,
    CLIENT_UNRESPONSIVE,
    PRICE_TOO_HIGH,
    CHOSE_COMPETITOR,
    DATE_UNAVAILABLE,
    NOT_INTERESTED,
    HANDLED_OFFLINE,
    DATA_CORRECTION,
    DUPLICATE_LEAD,
    RESTORED,
    HANDED_BACK,
    OTHER;

    /** The code as stored and sent, e.g. {@code client_request}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The reason with this code, if there is one. */
    public static Optional<MoveReason> fromCode(String code) {
        return Arrays.stream(values())
                .filter(reason -> reason.code().equals(code))
                .findFirst();
    }
}
