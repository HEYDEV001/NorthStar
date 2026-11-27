package com.northstar.workflow.internal;

import com.northstar.tenancy.IdempotencyKeyHeader;
import com.northstar.workflow.Workflows;
import com.northstar.workflow.Workflows.DraftSaved;
import com.northstar.workflow.Workflows.PublishedVersion;
import com.northstar.workflow.Workflows.WorkflowView;
import com.northstar.workflow.internal.WorkflowEditor.Written;
import com.northstar.workflow.internal.WorkflowRequests.CreateWorkflowRequest;
import com.northstar.workflow.internal.WorkflowRequests.PublishWorkflowRequest;
import com.northstar.workflow.internal.WorkflowResponses.DraftSavedResponse;
import com.northstar.workflow.internal.WorkflowResponses.ItemsResponse;
import com.northstar.workflow.internal.WorkflowResponses.ProblemResponse;
import com.northstar.workflow.internal.WorkflowResponses.ProblemsResponse;
import com.northstar.workflow.internal.WorkflowResponses.PublishedVersionResponse;
import com.northstar.workflow.internal.WorkflowResponses.WorkflowResponse;
import com.northstar.workflow.internal.WorkflowResponses.WorkflowSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workflow editor's API (step 3.1b; workflow-spec.md section 16), for admins only: staff get 403
 * {@code admin_only} before a key or a config is looked at, and another business's workflow is a 404 like a missing one
 * (P9).
 *
 * <p>The three writes need an {@value IdempotencyKeyHeader#NAME} header (P21; {@link WorkflowEditor}): 201 the first
 * time (create and publish, with {@code Location}; a draft save is always 200), 200 with the same result when the same
 * request comes again, 422 {@code idempotency_key_reused} with another request. Config bodies are read raw
 * ({@link DraftBody}); problems are 422 {@code workflow_config_invalid} with {@code details.problems}.
 */
@RestController
class WorkflowController {

    private static final String WORKFLOWS = "/api/v1/workflows/";

    private final Workflows workflows;
    private final WorkflowEditor editor;

    WorkflowController(Workflows workflows, WorkflowEditor editor) {
        this.workflows = workflows;
        this.editor = editor;
    }

    @GetMapping("/workflows")
    ItemsResponse<WorkflowSummary> list() {
        WorkflowEditor.requireAdmin();
        return new ItemsResponse<>(
                workflows.list().stream().map(WorkflowSummary::of).toList());
    }

    @PostMapping("/workflows")
    ResponseEntity<WorkflowResponse> create(
            @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
            @Valid @RequestBody CreateWorkflowRequest request) {
        WorkflowEditor.requireAdmin();
        String key = IdempotencyKeyHeader.require(idempotencyKey, "Creating a workflow");
        Written<WorkflowView> written = editor.create(request.name(), request.template(), key);
        WorkflowResponse body = WorkflowResponse.of(written.value());
        if (!written.created()) return ResponseEntity.ok(body);
        return ResponseEntity.created(URI.create(WORKFLOWS + body.id())).body(body);
    }

    @GetMapping("/workflows/{workflowId}")
    WorkflowResponse get(@PathVariable UUID workflowId) {
        WorkflowEditor.requireAdmin();
        return WorkflowResponse.of(workflows.get(workflowId));
    }

    @PutMapping("/workflows/{workflowId}/draft")
    DraftSavedResponse saveDraft(
            @PathVariable UUID workflowId,
            @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
            HttpServletRequest request) {
        WorkflowEditor.requireAdmin();
        String key = IdempotencyKeyHeader.require(idempotencyKey, "Saving a draft");
        Written<DraftSaved> written = editor.saveDraft(workflowId, DraftBody.read(request), key);
        return DraftSavedResponse.of(written.value());
    }

    @PostMapping("/workflows/{workflowId}/validate")
    ProblemsResponse validate(@PathVariable UUID workflowId, HttpServletRequest request) {
        WorkflowEditor.requireAdmin();
        return new ProblemsResponse(ProblemResponse.of(workflows.validate(workflowId, DraftBody.read(request))));
    }

    @PostMapping("/workflows/{workflowId}/publish")
    ResponseEntity<PublishedVersionResponse> publish(
            @PathVariable UUID workflowId,
            @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
            @Valid @RequestBody PublishWorkflowRequest request) {
        WorkflowEditor.requireAdmin();
        String key = IdempotencyKeyHeader.require(idempotencyKey, "Publishing a workflow");
        Written<PublishedVersion> written = editor.publish(workflowId, request.draftHash(), key);
        PublishedVersionResponse body = PublishedVersionResponse.of(written.value());
        if (!written.created()) return ResponseEntity.ok(body);
        return ResponseEntity.created(URI.create(WORKFLOWS + workflowId + "/versions/" + body.version()))
                .body(body);
    }

    @GetMapping("/workflows/{workflowId}/versions/{version}")
    PublishedVersionResponse version(@PathVariable UUID workflowId, @PathVariable int version) {
        WorkflowEditor.requireAdmin();
        return PublishedVersionResponse.of(workflows.publishedVersion(workflowId, version));
    }
}
