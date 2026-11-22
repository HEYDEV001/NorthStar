package com.northstar.integrations.internal;

import com.northstar.contacts.MessageTemplates;
import com.northstar.workflow.ActionView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Processes one stored template webhook change (step 2.8b; integration-specs.md section 2, Template status from Meta),
 * handed over by {@link WhatsAppInboundProcessor} with its row already locked, in the caller's transaction as the
 * system actor:
 *
 * <ul>
 *   <li>a status event that sets a status ({@link TemplateStatuses}) is applied to the template Meta knows by its ID,
 *       ordered by Meta's time for it ({@link MessageTemplates}): an older one changes nothing. Its sends then follow
 *       the status the template has ({@link TemplateDependents}, step 2.8c), an older event's included;
 *   <li>FLAGGED and LOCKED leave the status, and are logged;
 *   <li>UNARCHIVED requests a sync ({@link WhatsAppSyncRequests}), since Meta restores a previous status it does not
 *       name; an event Meta does not document requests one too and closes the row as {@value #EVENT_UNSUPPORTED};
 *   <li>an applied category change stores the new category; a notice 24 hours ahead is logged only; a category of an
 *       undocumented shape closes the row as {@value #CATEGORY_UNRECOGNISED};
 *   <li>a template the tenant does not have (or not yet) changes nothing, closes the row as {@value #TEMPLATE_UNKNOWN}
 *       and requests a sync, which inserts it with Meta's body: a webhook never creates a template (P9: another
 *       tenant's template ID finds nothing here);
 *   <li>an unusable slice is closed as {@code payload_invalid}.
 * </ul>
 *
 * <p>Locks: the webhook row, then the template's row, then its sends' contacts, actions and messages; a requested sync
 * is a new action row. Nothing here logs; the job
 * logs the outcome after the commit, with Northstar's IDs, statuses, categories and fixed codes only.
 */
@Component
class WhatsAppTemplateUpdateProcessor {

    static final String TEMPLATE_UNKNOWN = "template_unknown";
    static final String EVENT_UNSUPPORTED = "template_event_unsupported";
    static final String CATEGORY_UNRECOGNISED = "template_category_unrecognised";

    private final WebhookEventProcessing rows;
    private final MessageTemplates templates;
    private final TemplateDependents dependents;
    private final WhatsAppSyncRequests syncs;

    WhatsAppTemplateUpdateProcessor(
            WebhookEventProcessing rows,
            MessageTemplates templates,
            TemplateDependents dependents,
            WhatsAppSyncRequests syncs) {
        this.rows = rows;
        this.templates = templates;
        this.dependents = dependents;
        this.syncs = syncs;
    }

    sealed interface Outcome {}

    /**
     * A status applied (or stale, or unchanged); reason is Meta's rejection reason as a fixed code; sends is what the
     * template's status then did to its sends (step 2.8c).
     */
    record StatusApplied(MessageTemplates.Applied applied, String reason, TemplateDependents.Result sends)
            implements Outcome {}

    /** FLAGGED or LOCKED: the status stays. */
    record Noted(UUID templateId, String event) implements Outcome {}

    /** A sync was requested: for UNARCHIVED, an undocumented event, or an unknown template (templateId null). */
    record SyncRequested(UUID templateId, String code, UUID syncActionId) implements Outcome {}

    record CategoryApplied(MessageTemplates.Applied applied) implements Outcome {}

    /** Meta will move the template from current to upcoming in 24 hours; templateId null if unknown here. */
    record CategoryNotice(UUID templateId, String current, String upcoming) implements Outcome {}

    /** Closed with this fixed code; nothing changed. */
    record Rejected(String errorCode) implements Outcome {}

    /** Runs inside the caller's transaction, with the row locked and not yet processed. */
    Outcome process(UUID webhookEventId, WebhookEventProcessing.Locked row) {
        return switch (WhatsAppTemplateUpdate.parse(row.providerEventId(), row.payloadJson())) {
            case WhatsAppTemplateUpdate.Rejected rejected -> close(webhookEventId, rejected.errorCode());
            case WhatsAppTemplateUpdate.StatusChange change -> status(webhookEventId, change);
            case WhatsAppTemplateUpdate.CategoryChange change -> category(webhookEventId, change);
        };
    }

    private Outcome status(UUID webhookEventId, WhatsAppTemplateUpdate.StatusChange change) {
        String providerId = change.providerTemplateId();
        switch (TemplateStatuses.forEvent(change.event())) {
            case TemplateStatuses.SetStatus set -> {
                Optional<MessageTemplates.Applied> applied =
                        templates.applyStatus(providerId, set.status(), change.at());
                if (applied.isEmpty()) return unknown(webhookEventId);
                TemplateDependents.Result sends = dependents.apply(
                        applied.get().templateId(), applied.get().status());
                rows.finish(webhookEventId, null);
                return new StatusApplied(applied.get(), TemplateStatuses.reasonCode(change.reason()), sends);
            }
            case TemplateStatuses.Flagged flagged -> {
                return noted(webhookEventId, providerId, "flagged");
            }
            case TemplateStatuses.Locked locked -> {
                return noted(webhookEventId, providerId, "locked");
            }
            case TemplateStatuses.Unarchived unarchived -> {
                Optional<UUID> template = templates.findByProviderId(providerId);
                if (template.isEmpty()) return unknown(webhookEventId);
                ActionView sync = syncs.request();
                rows.finish(webhookEventId, null);
                return new SyncRequested(template.get(), "unarchived", sync.id());
            }
            case TemplateStatuses.Unsupported unsupported -> {
                UUID template = templates.findByProviderId(providerId).orElse(null);
                ActionView sync = syncs.request();
                rows.finish(webhookEventId, EVENT_UNSUPPORTED);
                return new SyncRequested(template, EVENT_UNSUPPORTED, sync.id());
            }
        }
    }

    private Outcome noted(UUID webhookEventId, String providerId, String event) {
        Optional<UUID> template = templates.findByProviderId(providerId);
        if (template.isEmpty()) return unknown(webhookEventId);
        rows.finish(webhookEventId, null);
        return new Noted(template.get(), event);
    }

    private Outcome category(UUID webhookEventId, WhatsAppTemplateUpdate.CategoryChange change) {
        if (!change.applied()) {
            UUID template =
                    templates.findByProviderId(change.providerTemplateId()).orElse(null);
            rows.finish(webhookEventId, null);
            return new CategoryNotice(
                    template,
                    change.newCategory().orElse("other"),
                    change.correctCategory().orElse("other"));
        }
        if (change.newCategory().isEmpty()) return close(webhookEventId, CATEGORY_UNRECOGNISED);
        Optional<MessageTemplates.Applied> applied = templates.applyCategory(
                change.providerTemplateId(), change.newCategory().get(), change.at());
        if (applied.isEmpty()) return unknown(webhookEventId);
        rows.finish(webhookEventId, null);
        return new CategoryApplied(applied.get());
    }

    private Outcome unknown(UUID webhookEventId) {
        ActionView sync = syncs.request();
        rows.finish(webhookEventId, TEMPLATE_UNKNOWN);
        return new SyncRequested(null, TEMPLATE_UNKNOWN, sync.id());
    }

    private Rejected close(UUID webhookEventId, String code) {
        rows.finish(webhookEventId, code);
        return new Rejected(code);
    }
}
