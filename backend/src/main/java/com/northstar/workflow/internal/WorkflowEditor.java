package com.northstar.workflow.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.api.UnprocessableException;
import com.northstar.tenancy.RequestKeys;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.Workflows.DraftSaved;
import com.northstar.workflow.Workflows.PublishedVersion;
import com.northstar.workflow.Workflows.WorkflowView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The workflow editor's writes over the API (step 3.1b; workflow-spec.md section 16): an admin creates the business's
 * workflow, saves its draft and publishes it, each with an {@code Idempotency-Key} (P21). In one transaction per write:
 * the key is claimed in {@code request_keys} with the request's hash and the record's ID, then the write is done; a
 * write that fails rolls the key back with it. A repeat answers with the first request's record, a draft save with its
 * first answer recomputed from the same body (never saved again, so a late retry cannot overwrite a newer draft); the
 * same key with another request is 422 {@code idempotency_key_reused}.
 *
 * <p>Logs carry IDs and counts only, never a name, a config, a hash or a key.
 */
@Service
class WorkflowEditor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEditor.class);

    private final WorkflowsService workflows;
    private final RequestKeys requestKeys;
    private final TransactionTemplate transactions;

    WorkflowEditor(WorkflowsService workflows, RequestKeys requestKeys, PlatformTransactionManager transactionManager) {
        this.workflows = workflows;
        this.requestKeys = requestKeys;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A write's result, and whether this request made it (false: a repeat of an earlier one with the same key). */
    record Written<T>(T value, boolean created) {}

    /**
     * Only admins use the editor: 403 {@code admin_only} for anyone else, before a key or a config is looked at
     * (build-document.md section 3: staff never edit workflows).
     */
    static void requireAdmin() {
        if (TenantScope.require().role() != Role.ADMIN) {
            throw new ForbiddenException("admin_only", "Only an admin can do this");
        }
    }

    Written<WorkflowView> create(String name, String template, String idempotencyKey) {
        requireAdmin();
        String hash = requestHash(RequestKeys.WORKFLOW_CREATE, name == null ? null : name.strip(), template);
        Written<WorkflowView> written = write(
                RequestKeys.WORKFLOW_CREATE,
                idempotencyKey,
                hash,
                UUID.randomUUID(),
                id -> workflows.create(id, name, template),
                workflows::get);
        log.info(
                written.created() ? "workflow.created workflow_id={}" : "workflow.create_repeated workflow_id={}",
                written.value().id());
        return written;
    }

    Written<DraftSaved> saveDraft(UUID workflowId, String draft, String idempotencyKey) {
        Objects.requireNonNull(workflowId, "workflowId");
        requireAdmin();
        String hash = requestHash(RequestKeys.WORKFLOW_DRAFT_SAVE, workflowId.toString(), sha256(draft));
        Written<DraftSaved> written = write(
                RequestKeys.WORKFLOW_DRAFT_SAVE,
                idempotencyKey,
                hash,
                workflowId,
                id -> workflows.saveDraft(id, draft),
                id -> workflows.replayDraft(id, draft));
        log.info(
                "workflow.draft_{} workflow_id={} warnings={}",
                written.created() ? "saved" : "save_repeated",
                workflowId,
                written.value().warnings().size());
        return written;
    }

    Written<PublishedVersion> publish(UUID workflowId, String draftHash, String idempotencyKey) {
        Objects.requireNonNull(workflowId, "workflowId");
        requireAdmin();
        String hash = requestHash(RequestKeys.WORKFLOW_PUBLISH, workflowId.toString(), draftHash);
        Written<PublishedVersion> written = write(
                RequestKeys.WORKFLOW_PUBLISH,
                idempotencyKey,
                hash,
                UUID.randomUUID(),
                versionId -> workflows.publish(versionId, workflowId, draftHash),
                versionId -> workflows.publishedVersion(versionId).orElseThrow(WorkflowEditor::keyReused));
        log.info(
                "workflow.{} workflow_id={} version={} version_id={}",
                written.created() ? "published" : "publish_repeated",
                workflowId,
                written.value().version(),
                written.value().id());
        return written;
    }

    /** Claims the key and writes, in one transaction: the write when the key is new, the earlier record on a repeat. */
    private <T> Written<T> write(
            String operation,
            String idempotencyKey,
            String requestHash,
            UUID newTargetId,
            Function<UUID, T> first,
            Function<UUID, T> repeat) {
        return transactions.execute(
                status -> switch (requestKeys.claim(operation, idempotencyKey, requestHash, newTargetId)) {
                    case RequestKeys.Reused reused -> throw keyReused();
                    case RequestKeys.Repeat earlier -> new Written<>(repeat.apply(earlier.targetId()), false);
                    case RequestKeys.Claimed claimed -> new Written<>(first.apply(claimed.targetId()), true);
                });
    }

    private static UnprocessableException keyReused() {
        return new UnprocessableException(
                "idempotency_key_reused", "This Idempotency-Key was already used for a different request");
    }

    /**
     * SHA-256, in lowercase hex, of the operation and the request as normalised, each field length-prefixed so no two
     * requests share an encoding (as manual lead entry's, step 2.10). Stored with the key, never logged.
     */
    static String requestHash(String operation, String... fields) {
        StringBuilder canonical = new StringBuilder(operation).append(":v1");
        for (String field : fields) {
            canonical.append('|');
            if (field == null) {
                canonical.append('-');
            } else {
                canonical.append(field.length()).append(':').append(field);
            }
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }
}
