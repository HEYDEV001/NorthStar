package com.northstar.workflow;

import java.util.Objects;

/**
 * One problem in a workflow config (workflow-spec.md section 15, format v1): where it is, as a JSONPath-like path from
 * {@code $}, and a fixed code. Never the value found there: a config is the business's own text.
 */
public record ConfigProblem(String path, String code) {

    public ConfigProblem {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(code, "code");
    }
}
