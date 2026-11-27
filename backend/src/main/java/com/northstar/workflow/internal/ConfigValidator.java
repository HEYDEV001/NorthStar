package com.northstar.workflow.internal;

import static com.northstar.workflow.internal.ConfigFormatV1.ADVANCE;
import static com.northstar.workflow.internal.ConfigFormatV1.JUNK;
import static com.northstar.workflow.internal.ConfigFormatV1.NEEDS_HUMAN;
import static com.northstar.workflow.internal.ConfigFormatV1.NEW;
import static com.northstar.workflow.internal.ConfigFormatV1.PAYMENT;
import static com.northstar.workflow.internal.ConfigFormatV1.QUALIFY;
import static com.northstar.workflow.internal.ConfigFormatV1.SCORE;
import static com.northstar.workflow.internal.ProblemCode.ACTION_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.CONFIG_NOT_OBJECT;
import static com.northstar.workflow.internal.ProblemCode.EVENT_NOT_ALLOWED;
import static com.northstar.workflow.internal.ProblemCode.EVENT_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.FIELD_REQUIRED;
import static com.northstar.workflow.internal.ProblemCode.FIELD_SERVER_SET;
import static com.northstar.workflow.internal.ProblemCode.FIELD_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.FILTER_RULE_ID_DUPLICATE;
import static com.northstar.workflow.internal.ProblemCode.SCHEMA_VERSION_UNSUPPORTED;
import static com.northstar.workflow.internal.ProblemCode.STAGE_KEY_DUPLICATE;
import static com.northstar.workflow.internal.ProblemCode.STAGE_NEEDS_HUMAN_MISSING;
import static com.northstar.workflow.internal.ProblemCode.STAGE_NEW_MISSING;
import static com.northstar.workflow.internal.ProblemCode.STAGE_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.STAGE_UNREACHABLE;
import static com.northstar.workflow.internal.ProblemCode.STEP_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.TEMPLATE_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.TIMER_DUPLICATE;
import static com.northstar.workflow.internal.ProblemCode.TIMER_TYPE_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.TYPE_INVALID;
import static com.northstar.workflow.internal.ProblemCode.VALUE_INVALID;

import com.northstar.workflow.ConfigProblem;
import com.northstar.workflow.internal.ConfigFormatV1.Param;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Checks a workflow config against format v1 (workflow-spec.md section 15, {@link ConfigFormatV1}) and reports every
 * problem at once, in document order, each with its path and fixed code. Pure: no database, no clock, no logging. The
 * same rules run on a draft save (as warnings) and on publish (any problem refuses it).
 *
 * <p>A first pass collects the stages (the first with each valid key counts; a later one is a duplicate), whether a
 * stage scores and whether filter rules lead to Junk, and which stages {@code new} can reach. The second walks the
 * document member by member and reports.
 */
final class ConfigValidator {

    /** A stage as the first pass sees it. key is null when missing or not a valid key. */
    private record Stage(
            int index, String key, String step, String paymentKind, boolean requiredFields, JsonNode exits) {}

    private final Problems problems = new Problems();
    private final ConditionRules conditions = new ConditionRules(problems);
    private final String workflowTemplate;
    private final List<Stage> stageList = new ArrayList<>();
    private final Map<String, Stage> stages = new LinkedHashMap<>();
    private boolean scoreStage;
    private boolean rulesToJunk;
    /** The stages new can reach; null when there is no stage new (then reachability is not checked). */
    private Set<String> reachable;

    private ConfigValidator(String workflowTemplate) {
        this.workflowTemplate = workflowTemplate;
    }

    /**
     * Every problem the document has. {@code workflowTemplate} is the workflow's own template (null if none), which a
     * config's {@code template} must equal.
     */
    static List<ConfigProblem> validate(JsonNode document, String workflowTemplate) {
        ConfigValidator validator = new ConfigValidator(workflowTemplate);
        validator.document(document);
        return validator.problems.list();
    }

    private void document(JsonNode node) {
        JsonPath root = JsonPath.ROOT;
        if (node == null || !node.isObject()) {
            problems.add(root, CONFIG_NOT_OBJECT);
            return;
        }
        survey(node);
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            JsonPath at = root.member(member.getKey());
            JsonNode value = member.getValue();
            switch (member.getKey()) {
                case "schema_version" -> schemaVersion(at, value);
                case "workflow_id", "version" -> problems.add(at, FIELD_SERVER_SET);
                case "template" -> template(at, value);
                case "approval_mode" -> bool(at, value);
                case "meta_conversion_stage" -> stageReference(at, value);
                case "quiet_hours" -> quietHours(at, value);
                case "stages" -> stages(at, value);
                case "filter_rules" -> filterRules(at, value);
                case "step_conditions" -> stepConditions(at, value);
                default -> problems.add(at, FIELD_UNKNOWN);
            }
        }
        required(root, node, "schema_version", "stages");
    }

    // The first pass.

    private void survey(JsonNode document) {
        JsonNode list = document.get("stages");
        if (list != null && list.isArray()) {
            for (int i = 0; i < list.size(); i++) {
                JsonNode item = list.get(i);
                if (!item.isObject()) {
                    stageList.add(null);
                    continue;
                }
                String key = string(item.get("key"));
                if (key != null && !ConfigFormatV1.KEY.matcher(key).matches()) key = null;
                String step = string(item.get("step"));
                JsonNode params = item.get("params");
                boolean withParams = params != null && params.isObject();
                String kind = PAYMENT.equals(step) && withParams ? string(params.get("kind")) : null;
                JsonNode fields = withParams ? params.get("required_fields") : null;
                boolean requiredFields =
                        QUALIFY.equals(step) && fields != null && fields.isArray() && !fields.isEmpty();
                Stage stage = new Stage(i, key, step, kind, requiredFields, item.get("exits"));
                stageList.add(stage);
                if (key != null) stages.putIfAbsent(key, stage);
                if (SCORE.equals(step)) scoreStage = true;
            }
        }
        JsonNode rules = document.get("filter_rules");
        if (rules != null && rules.isArray()) {
            for (int i = 0; i < rules.size(); i++) {
                JsonNode rule = rules.get(i);
                if (rule.isObject() && JUNK.equals(string(rule.get("then")))) rulesToJunk = true;
            }
        }
        if (stages.containsKey(NEW)) reachable = reach();
    }

    /**
     * From new, along exits, along filter rules from a scoring stage to Junk, and to the stages the engine moves any open
     * lead to (workflow-spec.md section 3).
     */
    private Set<String> reach() {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        List<String> starts = new ArrayList<>();
        starts.add(NEW);
        starts.addAll(ConfigFormatV1.ENGINE_TARGETS);
        for (String start : starts) {
            if (stages.containsKey(start) && seen.add(start)) queue.add(start);
        }
        while (!queue.isEmpty()) {
            Stage stage = stages.get(queue.poll());
            List<String> next = new ArrayList<>();
            JsonNode exits = stage.exits();
            if (exits != null && exits.isArray()) {
                for (int i = 0; i < exits.size(); i++) {
                    JsonNode exit = exits.get(i);
                    String to = exit.isObject() ? string(exit.get("to")) : null;
                    if (to != null) next.add(to);
                }
            }
            if (SCORE.equals(stage.step()) && rulesToJunk) next.add(JUNK);
            for (String key : next) {
                if (stages.containsKey(key) && seen.add(key)) queue.add(key);
            }
        }
        return seen;
    }

    // The document.

    private void schemaVersion(JsonPath at, JsonNode value) {
        if (!value.isIntegralNumber()) {
            problems.add(at, TYPE_INVALID);
        } else if (!value.canConvertToInt() || value.intValue() != ConfigFormatV1.SCHEMA_VERSION) {
            problems.add(at, SCHEMA_VERSION_UNSUPPORTED);
        }
    }

    private void template(JsonPath at, JsonNode value) {
        if (!value.isString()) {
            problems.add(at, TYPE_INVALID);
        } else if (!ConfigFormatV1.TEMPLATES.contains(value.stringValue())) {
            problems.add(at, TEMPLATE_UNKNOWN);
        } else if (!value.stringValue().equals(workflowTemplate)) {
            problems.add(at, VALUE_INVALID);
        }
    }

    private void stageReference(JsonPath at, JsonNode value) {
        if (!value.isString()) {
            problems.add(at, TYPE_INVALID);
        } else if (!stages.containsKey(value.stringValue())) {
            problems.add(at, STAGE_UNKNOWN);
        }
    }

    private void quietHours(JsonPath at, JsonNode value) {
        if (!value.isObject()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        for (Map.Entry<String, JsonNode> member : value.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            switch (member.getKey()) {
                case "start", "end" -> clock(memberAt, member.getValue());
                default -> problems.add(memberAt, FIELD_UNKNOWN);
            }
        }
        required(at, value, "start", "end");
        String start = string(value.get("start"));
        String end = string(value.get("end"));
        if (isClock(start) && isClock(end) && start.equals(end)) problems.add(at.member("end"), VALUE_INVALID);
    }

    private void clock(JsonPath at, JsonNode value) {
        if (!value.isString()) {
            problems.add(at, TYPE_INVALID);
        } else if (!isClock(value.stringValue())) {
            problems.add(at, VALUE_INVALID);
        }
    }

    private static boolean isClock(String value) {
        return value != null && ConfigFormatV1.CLOCK.matcher(value).matches();
    }

    // Stages.

    private void stages(JsonPath at, JsonNode value) {
        if (!value.isArray()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        if (value.isEmpty()) {
            problems.add(at, VALUE_INVALID);
            return;
        }
        if (!stages.containsKey(NEW)) problems.add(at, STAGE_NEW_MISSING);
        if (!stages.containsKey(NEEDS_HUMAN)) problems.add(at, STAGE_NEEDS_HUMAN_MISSING);
        for (int i = 0; i < value.size(); i++) stage(at.index(i), value.get(i), stageList.get(i));
    }

    private void stage(JsonPath at, JsonNode node, Stage stage) {
        if (stage == null) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        boolean first = stage.key() != null && stages.get(stage.key()).index() == stage.index();
        if (first && reachable != null && !reachable.contains(stage.key())) problems.add(at, STAGE_UNREACHABLE);
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            JsonNode value = member.getValue();
            switch (member.getKey()) {
                case "key" -> stageKey(memberAt, value, first);
                case "name" -> text(memberAt, value, 1, ConfigFormatV1.MAX_STAGE_NAME);
                case "step" -> step(memberAt, value);
                case "params" -> params(memberAt, value, node.get("step"));
                case "timers" -> timers(memberAt, value);
                case "exits" -> exits(memberAt, value, stage);
                case "approval" -> bool(memberAt, value);
                default -> problems.add(memberAt, FIELD_UNKNOWN);
            }
        }
        required(at, node, "key");
        boolean reserved = stage.key() != null && ConfigFormatV1.RESERVED_STAGES.containsKey(stage.key());
        if (node.get("name") == null && !reserved) problems.add(at.member("name"), FIELD_REQUIRED);
        requiredParams(at, node);
    }

    private void stageKey(JsonPath at, JsonNode value, boolean first) {
        if (!value.isString()) {
            problems.add(at, TYPE_INVALID);
        } else if (!ConfigFormatV1.KEY.matcher(value.stringValue()).matches()) {
            problems.add(at, VALUE_INVALID);
        } else if (!first) {
            problems.add(at, STAGE_KEY_DUPLICATE);
        }
    }

    private void step(JsonPath at, JsonNode value) {
        if (!value.isString()) {
            problems.add(at, TYPE_INVALID);
        } else if (!ConfigFormatV1.STEPS.containsKey(value.stringValue())) {
            problems.add(at, STEP_UNKNOWN);
        }
    }

    // Step parameters.

    private void params(JsonPath at, JsonNode value, JsonNode stepNode) {
        if (stepNode == null) {
            problems.add(at, FIELD_UNKNOWN); // parameters belong to a step
            return;
        }
        String step = string(stepNode);
        if (step == null || !ConfigFormatV1.STEPS.containsKey(step)) return; // the step itself is reported
        if (!value.isObject()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        Map<String, Param> spec = ConfigFormatV1.STEPS.get(step);
        String kind = PAYMENT.equals(step) ? string(value.get("kind")) : null;
        boolean otherKind = kind != null && spec.get("kind").values().contains(kind) && !ADVANCE.equals(kind);
        for (Map.Entry<String, JsonNode> member : value.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            Param param = spec.get(member.getKey());
            if (param == null) {
                problems.add(memberAt, FIELD_UNKNOWN);
            } else if (otherKind && "advance_pct".equals(member.getKey())) {
                problems.add(memberAt, VALUE_INVALID); // an advance percentage belongs to an advance payment only
            } else {
                param(memberAt, member.getValue(), param);
            }
        }
    }

    /** A payment step's kind, and an advance payment's percentage. */
    private void requiredParams(JsonPath at, JsonNode stage) {
        if (!PAYMENT.equals(string(stage.get("step")))) return;
        JsonNode params = stage.get("params");
        if (params != null && !params.isObject()) return; // reported as the wrong type
        JsonPath paramsAt = at.member("params");
        JsonNode kind = params == null ? null : params.get("kind");
        if (kind == null) {
            problems.add(paramsAt.member("kind"), FIELD_REQUIRED);
        } else if (ADVANCE.equals(string(kind)) && params.get("advance_pct") == null) {
            problems.add(paramsAt.member("advance_pct"), FIELD_REQUIRED);
        }
    }

    private void param(JsonPath at, JsonNode value, Param param) {
        switch (param.kind()) {
            case INTEGER -> integer(at, value, param.min(), param.max());
            case BOOLEAN -> bool(at, value);
            case TEXT -> text(at, value, param.min(), param.max());
            case CHOICE -> {
                if (!value.isString()) {
                    problems.add(at, TYPE_INVALID);
                } else if (!param.values().contains(value.stringValue())) {
                    problems.add(at, VALUE_INVALID);
                }
            }
            case TEXTS -> {
                if (!value.isArray()) {
                    problems.add(at, TYPE_INVALID);
                    return;
                }
                for (int i = 0; i < value.size(); i++) text(at.index(i), value.get(i), param.min(), param.max());
            }
            case LEAD_FIELDS -> leadFields(at, value);
            case BAND_THRESHOLDS -> bandThresholds(at, value);
        }
    }

    private void leadFields(JsonPath at, JsonNode value) {
        if (!value.isArray()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < value.size(); i++) {
            JsonNode field = value.get(i);
            if (!field.isString()) {
                problems.add(at.index(i), TYPE_INVALID);
            } else if (!ConfigFormatV1.isLeadField(field.stringValue()) || !seen.add(field.stringValue())) {
                problems.add(at.index(i), VALUE_INVALID);
            }
        }
    }

    private void bandThresholds(JsonPath at, JsonNode value) {
        if (!value.isObject()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        for (Map.Entry<String, JsonNode> member : value.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            switch (member.getKey()) {
                case "hot", "warm" -> integer(memberAt, member.getValue(), 0, 100);
                default -> problems.add(memberAt, FIELD_UNKNOWN);
            }
        }
        required(at, value, "hot", "warm");
        JsonNode hot = value.get("hot");
        JsonNode warm = value.get("warm");
        if (isInt(hot, 0, 100) && isInt(warm, 0, 100) && hot.intValue() <= warm.intValue()) {
            problems.add(at.member("hot"), VALUE_INVALID);
        }
    }

    // Timers.

    private void timers(JsonPath at, JsonNode value) {
        if (!value.isArray()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        Set<String> types = new HashSet<>();
        for (int i = 0; i < value.size(); i++) timer(at.index(i), value.get(i), types);
    }

    private void timer(JsonPath at, JsonNode node, Set<String> types) {
        if (!node.isObject()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        int waits = validWaits(node.get("after_hours"));
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            JsonNode value = member.getValue();
            switch (member.getKey()) {
                case "type" -> {
                    if (!value.isString()) {
                        problems.add(memberAt, TYPE_INVALID);
                    } else if (!ConfigFormatV1.TIMER_TYPES.contains(value.stringValue())) {
                        problems.add(memberAt, TIMER_TYPE_UNKNOWN);
                    } else if (!types.add(value.stringValue())) {
                        problems.add(memberAt, TIMER_DUPLICATE);
                    }
                }
                case "after_hours" -> afterHours(memberAt, value);
                case "max_attempts" -> integer(memberAt, value, 1, waits > 0 ? waits : Integer.MAX_VALUE);
                default -> problems.add(memberAt, FIELD_UNKNOWN);
            }
        }
        required(at, node, "type", "after_hours", "max_attempts");
    }

    private void afterHours(JsonPath at, JsonNode value) {
        if (!value.isArray()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        if (value.isEmpty()) {
            problems.add(at, VALUE_INVALID);
            return;
        }
        Integer previous = null;
        for (int i = 0; i < value.size(); i++) {
            JsonNode hours = value.get(i);
            if (!hours.isIntegralNumber()) {
                problems.add(at.index(i), TYPE_INVALID);
                previous = null;
            } else if (!isInt(hours, 1, Integer.MAX_VALUE) || (previous != null && hours.intValue() <= previous)) {
                problems.add(at.index(i), VALUE_INVALID);
                previous = isInt(hours, 1, Integer.MAX_VALUE) ? hours.intValue() : null;
            } else {
                previous = hours.intValue();
            }
        }
    }

    /** How many waits a valid after_hours list holds, else 0. */
    private static int validWaits(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty()) return 0;
        int previous = 0;
        for (int i = 0; i < value.size(); i++) {
            JsonNode hours = value.get(i);
            if (!isInt(hours, 1, Integer.MAX_VALUE) || hours.intValue() <= previous) return 0;
            previous = hours.intValue();
        }
        return value.size();
    }

    // Exits.

    private void exits(JsonPath at, JsonNode value, Stage stage) {
        if (!value.isArray()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        for (int i = 0; i < value.size(); i++) exit(at.index(i), value.get(i), stage);
    }

    private void exit(JsonPath at, JsonNode node, Stage stage) {
        if (!node.isObject()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            JsonNode value = member.getValue();
            switch (member.getKey()) {
                case "to" -> {
                    if (!value.isString()) {
                        problems.add(memberAt, TYPE_INVALID);
                    } else if (!stages.containsKey(value.stringValue())) {
                        problems.add(memberAt, STAGE_UNKNOWN);
                    } else if (value.stringValue().equals(stage.key())) {
                        problems.add(memberAt, VALUE_INVALID); // an exit leaves its stage
                    }
                }
                case "on" -> {
                    if (!value.isString()) {
                        problems.add(memberAt, TYPE_INVALID);
                    } else if (ConfigFormatV1.OPT_OUT_EVENT.equals(value.stringValue())) {
                        problems.add(memberAt, EVENT_NOT_ALLOWED);
                    } else if (!ConfigFormatV1.EVENTS.contains(value.stringValue())) {
                        problems.add(memberAt, EVENT_UNKNOWN);
                    }
                }
                case "if" -> conditions.check(memberAt, value, stage.requiredFields());
                // No action names exist in v1; step 3.5 names each step's actions.
                case "action" -> problems.add(memberAt, value.isString() ? ACTION_UNKNOWN : TYPE_INVALID);
                default -> problems.add(memberAt, FIELD_UNKNOWN);
            }
        }
        required(at, node, "to", "on");
    }

    // Filter rules and step conditions (workflow-spec.md section 7).

    private void filterRules(JsonPath at, JsonNode value) {
        if (!value.isArray()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        if (!value.isEmpty() && !scoreStage) problems.add(at, VALUE_INVALID); // filter rules run in the score step
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < value.size(); i++) filterRule(at.index(i), value.get(i), ids);
    }

    private void filterRule(JsonPath at, JsonNode node, Set<String> ids) {
        if (!node.isObject()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            JsonNode value = member.getValue();
            switch (member.getKey()) {
                case "id" -> {
                    if (!value.isString()) {
                        problems.add(memberAt, TYPE_INVALID);
                    } else if (!ConfigFormatV1.KEY.matcher(value.stringValue()).matches()) {
                        problems.add(memberAt, VALUE_INVALID);
                    } else if (!ids.add(value.stringValue())) {
                        problems.add(memberAt, FILTER_RULE_ID_DUPLICATE);
                    }
                }
                case "if" -> conditions.check(memberAt, value, false);
                case "then" -> {
                    if (!value.isString()) {
                        problems.add(memberAt, TYPE_INVALID);
                    } else if (!JUNK.equals(value.stringValue())) {
                        problems.add(memberAt, VALUE_INVALID);
                    } else if (!stages.containsKey(JUNK)) {
                        problems.add(memberAt, STAGE_UNKNOWN);
                    }
                }
                case "reason" -> text(memberAt, value, 1, ConfigFormatV1.MAX_REASON);
                default -> problems.add(memberAt, FIELD_UNKNOWN);
            }
        }
        required(at, node, "id", "if", "then", "reason");
    }

    private void stepConditions(JsonPath at, JsonNode value) {
        if (!value.isArray()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        for (int i = 0; i < value.size(); i++) stepCondition(at.index(i), value.get(i));
    }

    private void stepCondition(JsonPath at, JsonNode node) {
        if (!node.isObject()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            JsonPath memberAt = at.member(member.getKey());
            JsonNode value = member.getValue();
            switch (member.getKey()) {
                case "step" -> {
                    if (!value.isString()) {
                        problems.add(memberAt, TYPE_INVALID);
                    } else if (!usedStep(value.stringValue())) {
                        problems.add(memberAt, STEP_UNKNOWN);
                    }
                }
                case "enabled_if", "skip_if" -> conditions.check(memberAt, value, false);
                default -> problems.add(memberAt, FIELD_UNKNOWN);
            }
        }
        required(at, node, "step");
        boolean enabled = node.get("enabled_if") != null;
        boolean skipped = node.get("skip_if") != null;
        if (!enabled && !skipped) problems.add(at.member("enabled_if"), FIELD_REQUIRED);
        if (enabled && skipped) problems.add(at, VALUE_INVALID);
    }

    /** A step type some stage uses, or payment.<kind> for a payment stage of that kind. */
    private boolean usedStep(String name) {
        int dot = name.indexOf('.');
        if (dot < 0) {
            return ConfigFormatV1.STEPS.containsKey(name)
                    && stageList.stream().anyMatch(stage -> stage != null && name.equals(stage.step()));
        }
        String type = name.substring(0, dot);
        String kind = name.substring(dot + 1);
        return PAYMENT.equals(type)
                && stageList.stream()
                        .anyMatch(stage ->
                                stage != null && PAYMENT.equals(stage.step()) && kind.equals(stage.paymentKind()));
    }

    // Values.

    private void required(JsonPath at, JsonNode node, String... names) {
        for (String name : names) {
            if (node.get(name) == null) problems.add(at.member(name), FIELD_REQUIRED);
        }
    }

    private void bool(JsonPath at, JsonNode value) {
        if (!value.isBoolean()) problems.add(at, TYPE_INVALID);
    }

    private void integer(JsonPath at, JsonNode value, int min, int max) {
        if (!value.isIntegralNumber()) {
            problems.add(at, TYPE_INVALID);
        } else if (!isInt(value, min, max)) {
            problems.add(at, VALUE_INVALID);
        }
    }

    private static boolean isInt(JsonNode value, int min, int max) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToInt()
                && value.intValue() >= min
                && value.intValue() <= max;
    }

    /** min to max characters (code points), not blank. */
    private void text(JsonPath at, JsonNode value, int min, int max) {
        if (!value.isString()) {
            problems.add(at, TYPE_INVALID);
            return;
        }
        String text = value.stringValue();
        int length = text.codePointCount(0, text.length());
        if (text.isBlank() || length < min || length > max) problems.add(at, VALUE_INVALID);
    }

    private static String string(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
