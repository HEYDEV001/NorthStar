package com.northstar.workflow.internal;

import com.northstar.workflow.ConfigProblem;
import java.util.ArrayList;
import java.util.List;

/**
 * The problems found in one config, in the order they were found (document order), at most {@value #MAX}; past that,
 * one {@code too_many_problems} at {@code $} closes the list (workflow-spec.md section 15).
 */
final class Problems {

    static final int MAX = 500;

    private final List<ConfigProblem> found = new ArrayList<>();
    private boolean truncated;

    void add(JsonPath path, ProblemCode code) {
        if (found.size() >= MAX) {
            truncated = true;
            return;
        }
        found.add(new ConfigProblem(path.toString(), code.code()));
    }

    boolean isEmpty() {
        return found.isEmpty();
    }

    List<ConfigProblem> list() {
        if (!truncated) return List.copyOf(found);
        List<ConfigProblem> all = new ArrayList<>(found);
        all.add(new ConfigProblem(JsonPath.ROOT.toString(), ProblemCode.TOO_MANY_PROBLEMS.code()));
        return List.copyOf(all);
    }
}
