package com.northstar.workflow.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The workflow editor's JSON request bodies (step 3.1b), read by the app's strict mapper. A draft is not one of them: it
 * is the body itself ({@link DraftBody}). The name's length and the template are the service's to judge (422
 * {@code workflow_name_invalid}, {@code template_unknown}), as characters are counted as Unicode code points.
 */
final class WorkflowRequests {

    private WorkflowRequests() {}

    record CreateWorkflowRequest(
            @NotNull String name, @NotNull String template) {}

    /** The hash of the draft the admin reviewed, as a draft save or a read of the workflow returned it. */
    record PublishWorkflowRequest(
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String draftHash) {}
}
