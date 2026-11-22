package com.northstar.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The business's WhatsApp templates as Meta reports them (step 2.8b, integration-specs.md section 2): the template sync
 * and Meta's template webhooks write them only through here. Every call runs in the caller's tenant scope as the system
 * actor and joins the caller's transaction (MANDATORY); another tenant's template is the same as a missing one (P9).
 *
 * <p>Every write is ordered by {@code asOf}, Meta's time for what is written (a webhook's {@code entry.time}, or when a
 * sync's listing started): a write older than the template's {@code meta_updated_at} changes nothing
 * ({@link Outcome#STALE}), so a webhook Meta retries late never undoes a newer one, and the next sync corrects anything
 * a webhook got wrong. A write that changes nothing else still moves {@code meta_updated_at} forward.
 *
 * <p>Each call locks the template row ({@code for no key update}, which a message's foreign key check does not wait
 * for) and nothing else. The name and language are the template's identity and never change.
 */
public interface MessageTemplates {

    /**
     * A template as Meta lists it. category, body and variablesJson may be null (not sent, or not recognised): what is
     * stored is then kept. variablesJson is {@code {"format": "positional"|"named", "names": [...]}}.
     */
    record Listed(
            String providerTemplateId,
            String name,
            String language,
            String category,
            String body,
            String variablesJson,
            TemplateStatus status) {

        public Listed {
            Objects.requireNonNull(providerTemplateId, "providerTemplateId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(status, "status");
        }

        @Override
        public String toString() {
            return "Listed[status=" + status + "]";
        }
    }

    enum Outcome {
        /** A template Northstar did not have. */
        INSERTED,
        /** Something stored changed. */
        UPDATED,
        /**
         * The row of the same name and language held another of Meta's IDs, which the listing no longer has (Meta
         * deleted it and the name was used again): the row now follows the new template.
         */
        REPLACED,
        /** Nothing but meta_updated_at changed. */
        UNCHANGED,
        /** Older than what is stored; nothing changed. */
        STALE,
        /**
         * Another of Meta's templates, still listed, holds this name and language (two accounts of one business):
         * nothing changed.
         */
        NAME_TAKEN
    }

    /** What a write did, and the status and category before and after (null: none stored). */
    record Applied(
            UUID templateId,
            Outcome outcome,
            TemplateStatus previousStatus,
            TemplateStatus status,
            String previousCategory,
            String category) {

        public boolean statusChanged() {
            return previousStatus != status;
        }

        public boolean categoryChanged() {
            return previousCategory != null && category != null && !previousCategory.equals(category);
        }
    }

    /**
     * Applies one listed template: the row with its Meta ID; else the row of its name and language without one (a draft
     * or onboarding's), which takes the ID; else that row if its own ID is not in {@code listedIds}; else a new row.
     */
    Applied applyListed(Listed template, Set<String> listedIds, Instant asOf);

    /** Sets the status of the template Meta knows by this ID; empty if the tenant has none. */
    Optional<Applied> applyStatus(String providerTemplateId, TemplateStatus status, Instant asOf);

    /** Sets the category of the template Meta knows by this ID; empty if the tenant has none. */
    Optional<Applied> applyCategory(String providerTemplateId, String category, Instant asOf);

    /** The tenant's templates with a Meta ID not in {@code listedIds} and not deleted yet, by ID. */
    List<UUID> missingFrom(Set<String> listedIds);

    /** Marks one of those deleted (Meta no longer lists it); empty if the tenant has no such template. */
    Optional<Applied> markDeleted(UUID templateId, Instant asOf);

    /** Northstar's ID for the template Meta knows by this ID, for logs; empty if the tenant has none. */
    Optional<UUID> findByProviderId(String providerTemplateId);
}
