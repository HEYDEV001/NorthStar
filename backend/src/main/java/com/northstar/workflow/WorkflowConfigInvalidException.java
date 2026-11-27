package com.northstar.workflow;

import com.northstar.api.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 422 {@code workflow_config_invalid}: a workflow config that cannot be saved or published as it is (workflow-spec.md
 * sections 15 and 16). Every problem goes in {@code details.problems}, each as {@code {"path", "code"}}, in document
 * order; the message is fixed.
 */
public class WorkflowConfigInvalidException extends ApiException {

    public static final String CODE = "workflow_config_invalid";

    private final List<ConfigProblem> problems;

    public WorkflowConfigInvalidException(List<ConfigProblem> problems) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, CODE, "The workflow config has problems", details(problems));
        this.problems = List.copyOf(problems);
    }

    public List<ConfigProblem> problems() {
        return problems;
    }

    private static Map<String, Object> details(List<ConfigProblem> problems) {
        if (problems.isEmpty()) throw new IllegalArgumentException("A refused config has at least one problem");
        List<Map<String, String>> listed = problems.stream()
                .map(problem -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("path", problem.path());
                    entry.put("code", problem.code());
                    return entry;
                })
                .toList();
        return Map.of("problems", listed);
    }
}
