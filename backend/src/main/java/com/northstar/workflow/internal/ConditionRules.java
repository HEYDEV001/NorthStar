package com.northstar.workflow.internal;

import static com.northstar.workflow.internal.ProblemCode.CONDITION_FIELD_UNKNOWN;
import static com.northstar.workflow.internal.ProblemCode.CONDITION_INVALID;
import static com.northstar.workflow.internal.ProblemCode.CONDITION_OPERATOR_UNKNOWN;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Conditions in format v1 (workflow-spec.md section 7): one operator per object, over the fields section 7 names. Only
 * checked here; the engine evaluates them from step 3.4.
 */
final class ConditionRules {

    private final Problems problems;

    ConditionRules(Problems problems) {
        this.problems = problems;
    }

    /**
     * Checks one condition. {@code allRequiredFields} is whether it sits in an exit of a stage whose step has
     * required_fields, the only place {@code {"all_required_fields": true}} is allowed.
     */
    void check(JsonPath path, JsonNode condition, boolean allRequiredFields) {
        condition(path, condition, 1, allRequiredFields);
    }

    private void condition(JsonPath path, JsonNode node, int depth, boolean allRequiredFields) {
        if (depth > ConfigFormatV1.MAX_CONDITION_DEPTH || !node.isObject() || node.size() != 1) {
            problems.add(path, CONDITION_INVALID);
            return;
        }
        Map.Entry<String, JsonNode> only = node.properties().iterator().next();
        String operator = only.getKey();
        JsonNode operands = only.getValue();
        JsonPath at = path.member(operator);
        switch (operator) {
            case "all", "any" -> {
                if (!operands.isArray() || operands.isEmpty()) {
                    problems.add(at, CONDITION_INVALID);
                    return;
                }
                for (int i = 0; i < operands.size(); i++) {
                    condition(at.index(i), operands.get(i), depth + 1, allRequiredFields);
                }
            }
            case "not" -> condition(at, operands, depth + 1, allRequiredFields);
            case "eq", "neq", "gt", "gte", "lt", "lte", "contains" -> comparison(at, operands, false);
            case "in", "not_in" -> comparison(at, operands, true);
            case "exists" -> {
                if (!operands.isArray() || operands.size() != 1) {
                    problems.add(at, CONDITION_INVALID);
                    return;
                }
                field(at.index(0), operands.get(0));
            }
            case "within_zone" -> {
                if (!operands.isArray() || operands.size() != 2) {
                    problems.add(at, CONDITION_INVALID);
                    return;
                }
                field(at.index(0), operands.get(0));
                // A workspace setting: none exists yet (step 3.4 defines the service zone).
                problems.add(at.index(1), operands.get(1).isString() ? CONDITION_FIELD_UNKNOWN : CONDITION_INVALID);
            }
            case "all_required_fields" -> {
                if (!allRequiredFields || !operands.isBoolean() || !operands.booleanValue()) {
                    problems.add(at, CONDITION_INVALID);
                }
            }
            default -> problems.add(at, CONDITION_OPERATOR_UNKNOWN);
        }
    }

    /** [field, value], or with {@code list} [field, [values]] with at least one value. */
    private void comparison(JsonPath at, JsonNode operands, boolean list) {
        if (!operands.isArray() || operands.size() != 2) {
            problems.add(at, CONDITION_INVALID);
            return;
        }
        String field = field(at.index(0), operands.get(0));
        JsonPath valueAt = at.index(1);
        JsonNode value = operands.get(1);
        if (!list) {
            value(valueAt, field, value);
            return;
        }
        if (!value.isArray() || value.isEmpty()) {
            problems.add(valueAt, CONDITION_INVALID);
            return;
        }
        for (int i = 0; i < value.size(); i++) value(valueAt.index(i), field, value.get(i));
    }

    /** The field's name if it is one, else null (reported). */
    private String field(JsonPath at, JsonNode node) {
        if (!node.isString()) {
            problems.add(at, CONDITION_INVALID);
            return null;
        }
        if (!ConfigFormatV1.isConditionField(node.stringValue())) {
            problems.add(at, CONDITION_FIELD_UNKNOWN);
            return null;
        }
        return node.stringValue();
    }

    /** A string, number or boolean: an integer for budget (paise) and score, a boolean for the contact fields. */
    private void value(JsonPath at, String field, JsonNode node) {
        boolean scalar = node.isString() || node.isNumber() || node.isBoolean();
        boolean fits = true;
        if (field != null) {
            boolean integerOk = !ConfigFormatV1.INTEGER_FIELDS.contains(field) || node.isIntegralNumber();
            boolean booleanOk = !ConfigFormatV1.BOOLEAN_FIELDS.contains(field) || node.isBoolean();
            fits = integerOk && booleanOk;
        }
        if (!scalar || !fits) problems.add(at, CONDITION_INVALID);
    }
}
