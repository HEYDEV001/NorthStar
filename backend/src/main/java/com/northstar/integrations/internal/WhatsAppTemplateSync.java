package com.northstar.integrations.internal;

import com.northstar.contacts.MessageTemplates;
import com.northstar.contacts.TemplateStatus;
import com.northstar.workflow.ActionOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The template half of a WhatsApp sync (step 2.8b; integration-specs.md section 2, Template status from Meta), called
 * by {@link WhatsAppSyncHandler} on the worker:
 *
 * <ol>
 *   <li>{@link #fetch}: lists each of the business's WABAs page by page, outside any transaction, until the last page,
 *       the time budget's deadline (checked before each page) or {@value #MAX_PAGES} pages. A template listed twice
 *       (across pages, or WABAs) counts once, as first listed. The listing is complete only if every WABA reached its
 *       last page and every entry had an ID;
 *   <li>{@link #apply}: writes each listed template through {@link MessageTemplates}, each in its own short
 *       transaction, as of when the listing started; then, after a complete listing only, marks deleted the tenant's
 *       templates Meta no longer lists. A partial listing deletes nothing, so a cut-off sync never deletes a template.
 *       In each of those transactions the template's sends then follow the status it has ({@link TemplateDependents},
 *       step 2.8c), whether or not it changed.
 * </ol>
 *
 * <p>Body and variables come from Meta's answer only ({@link TemplateVariables}); a template whose status, name or
 * language Northstar cannot use is skipped (and never deleted for being unread). Logs carry action and template IDs,
 * statuses, categories, counts and fixed codes; never names, text, Meta's IDs or WABA IDs.
 */
@Component
class WhatsAppTemplateSync {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppTemplateSync.class);

    /** Per WABA: 10,000 templates, beyond Meta's limit of 6,000 for a verified business. */
    static final int MAX_PAGES = 100;

    static final String TEMPLATE_UNREADABLE = "template_unreadable";
    static final String TEMPLATE_STATUS_UNRECOGNISED = "template_status_unrecognised";

    private final WhatsAppAccountReader reader;
    private final MessageTemplates templates;
    private final TemplateDependents dependents;
    private final TransactionTemplate transactions;

    WhatsAppTemplateSync(
            WhatsAppAccountReader reader,
            MessageTemplates templates,
            TemplateDependents dependents,
            PlatformTransactionManager transactionManager) {
        this.reader = reader;
        this.templates = templates;
        this.dependents = dependents;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** One template written, and what its status then did to its sends. */
    private record Written(MessageTemplates.Applied applied, TemplateDependents.Result sends) {}

    sealed interface Fetch {}

    /** The listing: templates by Meta's ID, every ID seen, whether it is complete and why not, and pages read. */
    record Fetched(
            List<WhatsAppAccountReader.ListedTemplate> listed,
            Set<String> listedIds,
            boolean complete,
            String incompleteReason,
            int pages)
            implements Fetch {}

    record FetchFailed(ActionOutcome.Failed failure) implements Fetch {}

    /** What applying did. */
    record Applied(int templates, int changed, int deleted, int skipped) {}

    Fetch fetch(Collection<String> wabaIds, Secret token, Clock clock, Instant deadline) {
        Map<String, WhatsAppAccountReader.ListedTemplate> byId = new LinkedHashMap<>();
        String incomplete = null;
        int pages = 0;
        int unreadable = 0;
        accounts:
        for (String wabaId : new LinkedHashSet<>(wabaIds)) {
            String after = null;
            for (int page = 0; ; page++) {
                if (page >= MAX_PAGES) {
                    incomplete = "page_limit";
                    break;
                }
                if (!clock.instant().isBefore(deadline)) {
                    incomplete = "time_budget";
                    break accounts;
                }
                switch (reader.listTemplates(wabaId, after, token)) {
                    case WhatsAppAccountReader.Failed failed -> {
                        return new FetchFailed(failed.failure());
                    }
                    case WhatsAppAccountReader.TemplatePage read -> {
                        pages++;
                        unreadable += read.unreadable();
                        for (WhatsAppAccountReader.ListedTemplate template : read.templates()) {
                            byId.putIfAbsent(template.id(), template);
                        }
                        after = read.after();
                    }
                }
                if (after == null) break;
            }
        }
        // An entry without an ID could be any template: delete nothing.
        if (incomplete == null && unreadable > 0) incomplete = "unreadable_entries";
        return new Fetched(
                List.copyOf(byId.values()), Set.copyOf(byId.keySet()), incomplete == null, incomplete, pages);
    }

    Applied apply(Fetched fetched, Instant asOf, UUID actionId) {
        int changed = 0;
        int skipped = 0;
        for (WhatsAppAccountReader.ListedTemplate template : fetched.listed()) {
            Optional<MessageTemplates.Listed> usable = usable(template, actionId);
            if (usable.isEmpty()) {
                skipped++;
                continue;
            }
            Written written = transactions.execute(status -> {
                MessageTemplates.Applied applied = templates.applyListed(usable.get(), fetched.listedIds(), asOf);
                return new Written(applied, dependents.apply(applied.templateId(), applied.status()));
            });
            if (logApplied(actionId, written.applied())) changed++;
            logSends(written);
        }
        int deleted = 0;
        if (fetched.complete()) {
            List<UUID> missing = transactions.execute(status -> templates.missingFrom(fetched.listedIds()));
            for (UUID templateId : missing) {
                Optional<Written> written = transactions.execute(status -> templates
                        .markDeleted(templateId, asOf)
                        .map(applied ->
                                new Written(applied, dependents.apply(applied.templateId(), applied.status()))));
                if (written.isEmpty()) continue;
                MessageTemplates.Applied applied = written.get().applied();
                if (applied.outcome() == MessageTemplates.Outcome.UPDATED) {
                    deleted++;
                    log.info("whatsapp.sync.template_missing action_id={} template_id={}", actionId, templateId);
                    TemplateLogs.statusChanged(
                            templateId, applied.previousStatus(), TemplateStatus.DELETED, "sync", "none");
                }
                logSends(written.get());
            }
        } else {
            log.warn(
                    "whatsapp.sync.templates_partial action_id={} reason={} pages={}",
                    actionId,
                    fetched.incompleteReason(),
                    fetched.pages());
        }
        log.info(
                "whatsapp.sync.templates action_id={} pages={} templates={} changed={} deleted={} skipped={}"
                        + " complete={}",
                actionId,
                fetched.pages(),
                fetched.listed().size(),
                changed,
                deleted,
                skipped,
                fetched.complete());
        return new Applied(fetched.listed().size(), changed, deleted, skipped);
    }

    /** A listed template Northstar can store, or empty (logged with a fixed code, never its values). */
    private static Optional<MessageTemplates.Listed> usable(
            WhatsAppAccountReader.ListedTemplate template, UUID actionId) {
        if (template.name() == null
                || template.name().isEmpty()
                || template.name().length() > 512
                || template.language() == null
                || template.language().isEmpty()
                || template.language().length() > 16) {
            log.warn("whatsapp.sync.template_skipped action_id={} code={}", actionId, TEMPLATE_UNREADABLE);
            return Optional.empty();
        }
        Optional<TemplateStatus> status = TemplateStatuses.forListed(template.status());
        if (status.isEmpty()) {
            log.warn("whatsapp.sync.template_skipped action_id={} code={}", actionId, TEMPLATE_STATUS_UNRECOGNISED);
            return Optional.empty();
        }
        return Optional.of(new MessageTemplates.Listed(
                template.id(),
                template.name(),
                template.language(),
                TemplateStatuses.category(template.category()).orElse(null),
                template.body(),
                TemplateVariables.json(template.parameterFormat(), template.body()),
                status.get()));
    }

    private static void logSends(Written written) {
        if (written.sends().any()) {
            TemplateLogs.sends(written.applied().templateId(), written.applied().status(), written.sends(), "sync");
        }
    }

    /** Logs what applying one template did; true if it changed anything stored. */
    private static boolean logApplied(UUID actionId, MessageTemplates.Applied applied) {
        switch (applied.outcome()) {
            case INSERTED -> {
                log.info(
                        "whatsapp.sync.template_inserted action_id={} template_id={} status={} category={}",
                        actionId,
                        applied.templateId(),
                        applied.status().code(),
                        applied.category() == null ? "none" : applied.category());
                return true;
            }
            case UPDATED, REPLACED -> {
                log.info(
                        "whatsapp.sync.template_updated action_id={} template_id={} replaced={}",
                        actionId,
                        applied.templateId(),
                        applied.outcome() == MessageTemplates.Outcome.REPLACED);
                if (applied.statusChanged()) {
                    TemplateLogs.statusChanged(
                            applied.templateId(), applied.previousStatus(), applied.status(), "sync", "none");
                }
                if (applied.categoryChanged()) {
                    TemplateLogs.categoryChanged(
                            applied.templateId(), applied.previousCategory(), applied.category(), "sync");
                }
                return true;
            }
            case STALE -> {
                log.info("whatsapp.sync.template_stale action_id={} template_id={}", actionId, applied.templateId());
                return false;
            }
            case NAME_TAKEN -> {
                log.warn(
                        "whatsapp.sync.template_name_taken action_id={} template_id={}",
                        actionId,
                        applied.templateId());
                return false;
            }
            case UNCHANGED -> {
                return false;
            }
        }
        return false;
    }
}
