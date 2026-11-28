package com.northstar.workflow.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the state machine reads of a published version's config (step 3.3, workflow-spec.md sections 3 and 15): its
 * stages in order, each stage's step and exits, and its filter rules' IDs. A version is run only when its
 * {@code schema_version} is one the engine knows ({@value ConfigFormatV1#SCHEMA_VERSION}); one with none (the 2.11 dev
 * seed's version 1, from before step 3.1) or another one is unsupported, and so is one whose shape cannot be read
 * (versions from before step 3.1 were never validated).
 */
final class WorkflowGraph {

    /** An exit of a stage: where it leads, the event it waits for, and whether it has a condition ({@code if}). */
    record Exit(String to, String on, boolean conditional) {}

    private record Stage(String step, List<Exit> exits) {}

    /** A stored config: its graph, or why it cannot be run (exactly one of the two). */
    record Parsed(WorkflowGraph graph, String unsupported) {

        Parsed {
            if ((graph == null) == (unsupported == null)) throw new IllegalArgumentException("A graph or a reason");
        }
    }

    static final String SCHEMA_VERSION_MISSING = "schema_version_missing";
    static final String SCHEMA_VERSION_UNKNOWN = "schema_version_unknown";
    static final String CONFIG_UNREADABLE = "config_unreadable";

    private final Map<String, Stage> stages;
    private final Set<String> junkRules;

    private WorkflowGraph(Map<String, Stage> stages, Set<String> junkRules) {
        this.stages = stages;
        this.junkRules = junkRules;
    }

    /** Reads a config as the database stores it (jsonb text). */
    static Parsed parse(String storedConfig) {
        ObjectNode config;
        try {
            config = ConfigJson.stored(storedConfig);
        } catch (RuntimeException unreadable) {
            return unsupported(CONFIG_UNREADABLE);
        }
        JsonNode schemaVersion = config.get("schema_version");
        if (schemaVersion == null) return unsupported(SCHEMA_VERSION_MISSING);
        if (!schemaVersion.isIntegralNumber()
                || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != ConfigFormatV1.SCHEMA_VERSION) {
            return unsupported(SCHEMA_VERSION_UNKNOWN);
        }
        JsonNode stageList = config.get("stages");
        if (stageList == null || !stageList.isArray()) return unsupported(CONFIG_UNREADABLE);
        Map<String, Stage> stages = new LinkedHashMap<>();
        for (JsonNode stage : stageList) {
            String key = text(stage, "key");
            if (key == null || stages.containsKey(key)) return unsupported(CONFIG_UNREADABLE);
            JsonNode stepNode = stage.get("step");
            if (stepNode != null && !stepNode.isString()) return unsupported(CONFIG_UNREADABLE);
            List<Exit> exits = new ArrayList<>();
            JsonNode exitList = stage.get("exits");
            if (exitList != null) {
                if (!exitList.isArray()) return unsupported(CONFIG_UNREADABLE);
                for (JsonNode exit : exitList) {
                    String to = text(exit, "to");
                    String on = text(exit, "on");
                    if (to == null || on == null) return unsupported(CONFIG_UNREADABLE);
                    exits.add(new Exit(to, on, exit.get("if") != null));
                }
            }
            stages.put(key, new Stage(stepNode == null ? null : stepNode.stringValue(), List.copyOf(exits)));
        }
        Set<String> junkRules = new TreeSet<>();
        JsonNode rules = config.get("filter_rules");
        if (rules != null) {
            if (!rules.isArray()) return unsupported(CONFIG_UNREADABLE);
            for (JsonNode rule : rules) {
                String id = text(rule, "id");
                if (id == null) return unsupported(CONFIG_UNREADABLE);
                if (ConfigFormatV1.JUNK.equals(text(rule, "then"))) junkRules.add(id);
            }
        }
        return new Parsed(new WorkflowGraph(stages, Set.copyOf(junkRules)), null);
    }

    /** Whether the version declares this stage. */
    boolean declares(String stage) {
        return stage != null && stages.containsKey(stage);
    }

    /** The exits of a declared stage, in list order (none for an undeclared one). */
    List<Exit> exits(String stage) {
        Stage found = stages.get(stage);
        return found == null ? List.of() : found.exits();
    }

    /** Whether this declared stage's step is {@code score}, the stage filter rules send to Junk from. */
    boolean isScoreStage(String stage) {
        Stage found = stages.get(stage);
        return found != null && ConfigFormatV1.SCORE.equals(found.step());
    }

    /** Whether the version has a filter rule with this ID whose {@code then} is Junk. */
    boolean isJunkRule(String ruleId) {
        return ruleId != null && junkRules.contains(ruleId);
    }

    /** Whether a stage closes a lead (reserved keys only, so the same in every config). */
    static boolean isClosing(String stage) {
        return ConfigFormatV1.CLOSING_STAGES.contains(stage);
    }

    private static Parsed unsupported(String reason) {
        return new Parsed(null, reason);
    }

    private static String text(JsonNode node, String member) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(member);
        return value != null && value.isString() ? Objects.requireNonNull(value.stringValue()) : null;
    }
}
