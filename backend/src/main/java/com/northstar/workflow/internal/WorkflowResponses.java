package com.northstar.workflow.internal;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.northstar.workflow.ConfigProblem;
import com.northstar.workflow.Workflows.DraftSaved;
import com.northstar.workflow.Workflows.PublishedVersion;
import com.northstar.workflow.Workflows.WorkflowView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The workflow editor's answers (step 3.1b; workflow-spec.md section 16). Drafts and configs are the JSON exactly as the
 * database stores it (a valid JSON object), written into the answer as it is, so numbers keep their form.
 */
final class WorkflowResponses {

    private WorkflowResponses() {}

    record ProblemResponse(String path, String code) {

        static List<ProblemResponse> of(List<ConfigProblem> problems) {
            return problems.stream()
                    .map(problem -> new ProblemResponse(problem.path(), problem.code()))
                    .toList();
        }
    }

    record ItemsResponse<T>(List<T> items) {}

    /** A workflow in a list: everything but its draft. */
    record WorkflowSummary(
            UUID id,
            String name,
            String template,
            UUID currentVersionId,
            Integer currentVersion,
            String draftHash,
            Instant createdAt,
            Instant updatedAt) {

        static WorkflowSummary of(WorkflowView view) {
            return new WorkflowSummary(
                    view.id(),
                    view.name(),
                    view.template(),
                    view.currentVersionId(),
                    view.currentVersion(),
                    view.draftHash(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    record WorkflowResponse(
            UUID id,
            String name,
            String template,
            UUID currentVersionId,
            Integer currentVersion,
            @JsonRawValue String draft,
            String draftHash,
            Instant createdAt,
            Instant updatedAt) {

        static WorkflowResponse of(WorkflowView view) {
            return new WorkflowResponse(
                    view.id(),
                    view.name(),
                    view.template(),
                    view.currentVersionId(),
                    view.currentVersion(),
                    view.draftConfig(),
                    view.draftHash(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    record DraftSavedResponse(String draftHash, List<ProblemResponse> warnings) {

        static DraftSavedResponse of(DraftSaved saved) {
            return new DraftSavedResponse(saved.draftHash(), ProblemResponse.of(saved.warnings()));
        }
    }

    record ProblemsResponse(List<ProblemResponse> problems) {}

    record PublishedVersionResponse(
            UUID id,
            UUID workflowId,
            int version,
            @JsonRawValue String config,
            UUID publishedBy,
            Instant publishedAt) {

        static PublishedVersionResponse of(PublishedVersion version) {
            return new PublishedVersionResponse(
                    version.id(),
                    version.workflowId(),
                    version.version(),
                    version.config(),
                    version.publishedBy(),
                    version.publishedAt());
        }
    }
}
