package com.northstar.integrations.internal;

import com.northstar.contacts.TemplateStatus;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Meta's template statuses and status-webhook events as Northstar's (step 2.8b; integration-specs.md section 2, Template
 * status from Meta; Meta's message templates reference and {@code message_template_status_update} reference, checked
 * 21 and 22 Nov 2026). Decided 22 Nov 2026: LIMIT_EXCEEDED counts as rejected, and ARCHIVED is its own status.
 */
final class TemplateStatuses {

    /** A listed template's status, or a webhook event that sets one. */
    private static final Map<String, TemplateStatus> STATUSES = Map.ofEntries(
            Map.entry("PENDING", TemplateStatus.SUBMITTED),
            Map.entry("IN_APPEAL", TemplateStatus.SUBMITTED),
            Map.entry("APPROVED", TemplateStatus.APPROVED),
            Map.entry("REINSTATED", TemplateStatus.APPROVED), // "no longer flagged or disabled and can be sent"
            Map.entry("PAUSED", TemplateStatus.PAUSED),
            Map.entry("REJECTED", TemplateStatus.REJECTED),
            Map.entry("LIMIT_EXCEEDED", TemplateStatus.REJECTED),
            Map.entry("DISABLED", TemplateStatus.DISABLED),
            Map.entry("PENDING_DELETION", TemplateStatus.DELETED),
            Map.entry("DELETED", TemplateStatus.DELETED),
            Map.entry("ARCHIVED", TemplateStatus.ARCHIVED));

    /** Rejection reasons Meta documents; any other value is logged as "other". */
    private static final Set<String> REASONS = Set.of(
            "ABUSIVE_CONTENT",
            "CATEGORY_NOT_AVAILABLE",
            "INCORRECT_CATEGORY",
            "INVALID_FORMAT",
            "NONE",
            "PROMOTIONAL",
            "SCAM",
            "TAG_CONTENT_MISMATCH");

    /** The statuses a send cannot go out on, whose change is the admin's WARN until Phase 8. */
    private static final Set<TemplateStatus> BLOCKING = Set.of(
            TemplateStatus.REJECTED,
            TemplateStatus.PAUSED,
            TemplateStatus.DISABLED,
            TemplateStatus.DELETED,
            TemplateStatus.ARCHIVED);

    /** A send of a template that is not approved yet (step 2.6a): draft or submitted. */
    static final String TEMPLATE_NOT_APPROVED = "template_not_approved";

    /** A send of a paused template is blocked until Meta approves it again (step 2.8c); also Meta's 132015. */
    static final String TEMPLATE_PAUSED = "template_paused";

    static final String TEMPLATE_REJECTED = "template_rejected";

    /** Also Meta's 132016, the same status seen at send time. */
    static final String TEMPLATE_DISABLED = "template_disabled";

    static final String TEMPLATE_DELETED = "template_deleted";
    static final String TEMPLATE_ARCHIVED = "template_archived";

    private TemplateStatuses() {}

    /**
     * The fixed code a send stops with on this status (step 2.8c, W34; decided 22 Nov 2026), or empty for approved, the
     * one status a send goes out on. {@value #TEMPLATE_PAUSED} blocks the send until the template is approved again;
     * every other code fails it.
     */
    static Optional<String> sendRefusal(TemplateStatus status) {
        return Optional.ofNullable(
                switch (status) {
                    case APPROVED -> null;
                    case DRAFT, SUBMITTED -> TEMPLATE_NOT_APPROVED;
                    case PAUSED -> TEMPLATE_PAUSED;
                    case REJECTED -> TEMPLATE_REJECTED;
                    case DISABLED -> TEMPLATE_DISABLED;
                    case DELETED -> TEMPLATE_DELETED;
                    case ARCHIVED -> TEMPLATE_ARCHIVED;
                });
    }

    /** What a status webhook's event does. */
    sealed interface Event {}

    /** The template takes this status. */
    record SetStatus(TemplateStatus status) implements Event {}

    /** Meta flagged the template for low quality: its status stays, and it is logged as a warning. */
    record Flagged() implements Event {}

    /** Meta locked the template (no edits): its status stays, logged. */
    record Locked() implements Event {}

    /** Meta restored an archived template to a previous status the event does not name: a sync reads it. */
    record Unarchived() implements Event {}

    /** An event Meta does not document: nothing changes, and a sync reads the template's status. */
    record Unsupported() implements Event {}

    static Event forEvent(String event) {
        if (event == null) return new Unsupported();
        TemplateStatus status = STATUSES.get(event);
        if (status != null) return new SetStatus(status);
        return switch (event) {
            case "FLAGGED" -> new Flagged();
            case "LOCKED" -> new Locked();
            case "UNARCHIVED" -> new Unarchived();
            default -> new Unsupported();
        };
    }

    /** A listed template's status; empty for a value Meta does not document. */
    static Optional<TemplateStatus> forListed(String status) {
        return Optional.ofNullable(status == null ? null : STATUSES.get(status));
    }

    /** Meta's rejection reason as a lowercase fixed code for logs, "other" if undocumented, "none" if absent. */
    static String reasonCode(String reason) {
        if (reason == null) return "none";
        return REASONS.contains(reason) ? reason.toLowerCase(java.util.Locale.ROOT) : "other";
    }

    /** Whether a send cannot go out on this status. */
    static boolean blocking(TemplateStatus status) {
        return BLOCKING.contains(status);
    }

    /** Meta's category as stored (MARKETING, UTILITY, AUTHENTICATION and any later one), or empty if not one. */
    static Optional<String> category(String category) {
        return category != null && category.matches("[A-Z][A-Z_]{0,31}") ? Optional.of(category) : Optional.empty();
    }
}
