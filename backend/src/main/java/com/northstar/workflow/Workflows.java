package com.northstar.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A business's workflow: created from an industry template, edited as a draft, published as immutable versions
 * (workflow-spec.md sections 15 and 16, step 3.1). Every method runs in the current tenant, in its own transaction or
 * the caller's, and only for an admin or the system actor: staff never edit workflows (403 {@code admin_only}), and
 * another business's workflow is a 404 (P9).
 *
 * <p>One workflow per business for MVP (data-model.md section 15): a second cannot be created, and none is published
 * while another of the business's workflows is. Publishing never touches leads: new leads take the new current version
 * ({@link LeadIntake#publishedWorkflowVersion}), and every lead keeps the version it started on (W36).
 */
public interface Workflows {

    /**
     * Creates the business's workflow, with no draft and no version yet, and records {@code workflow.created}. The
     * template is an industry template key (workflow-spec.md section 14), else 422 {@code template_unknown}; the name,
     * stored without surrounding spaces, is 1–100 characters, else 422 {@code workflow_name_invalid}. 409
     * {@code workflow_exists} if the business already has a workflow.
     */
    WorkflowView create(String name, String template);

    /** The business's workflow: the published one, else the oldest. Empty when it has none. */
    Optional<WorkflowView> current();

    /** The workflow, or 404. */
    WorkflowView get(UUID workflowId);

    /**
     * Stores the draft as sent and returns its problems as warnings (drafts may be incomplete) with the stored draft's
     * hash, which {@link #publish} takes back. Only a draft that cannot be stored is refused, with 422
     * {@code workflow_config_invalid}: not JSON, a member named twice, more than 256 KB, or not a JSON object.
     */
    DraftSaved saveDraft(UUID workflowId, String draft);

    /** Every problem the document has, as publishing would see them; nothing is stored. */
    List<ConfigProblem> validate(UUID workflowId, String draft);

    /**
     * Publishes the stored draft as the workflow's next version and makes it the current one, recording
     * {@code workflow.published}. 409 {@code draft_changed} if the draft's hash is not {@code draftHash} (it changed
     * since it was reviewed), {@code draft_missing} without a draft, {@code another_workflow_published} if another of the
     * business's workflows is published; 422 {@code workflow_config_invalid} with every problem.
     */
    PublishedVersion publish(UUID workflowId, String draftHash);

    /** A published version of the workflow, or 404. */
    PublishedVersion publishedVersion(UUID workflowId, int version);

    /**
     * A workflow. currentVersionId and currentVersion are null until it is first published; draftConfig and draftHash
     * are null without a draft. draftConfig is the JSON text as stored.
     */
    record WorkflowView(
            UUID id,
            String name,
            String template,
            UUID currentVersionId,
            Integer currentVersion,
            String draftConfig,
            String draftHash,
            Instant createdAt,
            Instant updatedAt) {

        public WorkflowView {
            Objects.requireNonNull(id, "id");
        }
    }

    /** A saved draft: its hash (SHA-256 of the stored JSON text, lowercase hex) and its problems, as warnings. */
    record DraftSaved(String draftHash, List<ConfigProblem> warnings) {

        public DraftSaved {
            Objects.requireNonNull(draftHash, "draftHash");
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * A published version: its config as stored (with {@code workflow_id} and {@code version} written into it), and the
     * membership that published it (null: the system actor).
     */
    record PublishedVersion(
            UUID id, UUID workflowId, int version, String config, UUID publishedBy, Instant publishedAt) {

        public PublishedVersion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(workflowId, "workflowId");
            Objects.requireNonNull(config, "config");
        }
    }
}
