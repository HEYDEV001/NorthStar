package com.northstar.contacts;

import java.util.Optional;

/**
 * A WhatsApp message template's status, as stored in message_templates.status (data-model.md section 4; Meta's statuses
 * mapped to these in integration-specs.md section 2, step 2.8b). Only an approved template can be sent.
 */
public enum TemplateStatus {
    /** Not submitted to Meta yet. */
    DRAFT("draft"),
    /** Submitted and waiting for Meta's review, or appealed (Meta's PENDING, IN_APPEAL). */
    SUBMITTED("submitted"),
    APPROVED("approved"),
    /** Rejected by Meta's review, or refused for the account's template limit (REJECTED, LIMIT_EXCEEDED). */
    REJECTED("rejected"),
    /** Paused by Meta for low quality; Meta unpauses it, or disables it after the third pause. */
    PAUSED("paused"),
    DISABLED("disabled"),
    /** Deleted, or being deleted (DELETED, PENDING_DELETION), or no longer in Meta's listing. */
    DELETED("deleted"),
    /** Archived by Meta after a year without sends; deleted after 28 days unless unarchived. */
    ARCHIVED("archived");

    private final String code;

    TemplateStatus(String code) {
        this.code = code;
    }

    /** message_templates.status. */
    public String code() {
        return code;
    }

    public static Optional<TemplateStatus> fromCode(String code) {
        for (TemplateStatus status : values()) {
            if (status.code.equals(code)) return Optional.of(status);
        }
        return Optional.empty();
    }
}
