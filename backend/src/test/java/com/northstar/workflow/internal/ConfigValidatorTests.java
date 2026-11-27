package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.workflow.ConfigProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Format v1 of a workflow config (workflow-spec.md sections 7 and 15, step 3.1): what passes, one refusal per code, and
 * every problem at once, in document order, each with its path. Pure: no database. The configs here are neutral test
 * values, never a business's.
 */
class ConfigValidatorTests {

    /** The smallest config v1 accepts: the stage every lead starts in and the one every config needs beside it. */
    private static final String MINIMAL =
            json("{'schema_version': 1, 'stages': [{'key': 'new', 'name': 'New'}, {'key': 'needs_human'}]}");

    /** Every member, step type and parameter of v1, every condition operator but within_zone, all reachable. */
    private static final String FULL = json("""
            {
              'schema_version': 1,
              'template': 'shoot_production',
              'approval_mode': true,
              'meta_conversion_stage': 'paying',
              'quiet_hours': {'start': '22:00', 'end': '08:00'},
              'stages': [
                {'key': 'new', 'name': 'Start', 'exits': [{'to': 'asking', 'on': 'message.received'}]},
                {
                  'key': 'asking', 'name': 'Asking', 'step': 'qualify',
                  'params': {
                    'questions': ['Question one?', 'Question two?'],
                    'required_fields': ['service', 'budget', 'time', 'requirements.colour'],
                    'max_turns': 5, 'language': 'en', 'tone': 'plain'
                  },
                  'timers': [{'type': 'follow_up', 'after_hours': [2, 6], 'max_attempts': 2}],
                  'exits': [{'to': 'scoring', 'on': 'ai.fields_extracted', 'if': {'all_required_fields': true}}]
                },
                {
                  'key': 'scoring', 'name': 'Scoring', 'step': 'score',
                  'params': {'band_thresholds': {'hot': 80, 'warm': 40}, 'meta_lead_event': false},
                  'exits': [{'to': 'calling', 'on': 'staff.action', 'if': {'all': [
                    {'gte': ['score', 40]}, {'in': ['band', ['hot', 'warm']]}, {'not': {'eq': ['opt_out', true]}}]}}]
                },
                {
                  'key': 'calling', 'name': 'Calling', 'step': 'call',
                  'params': {'duration_min': 30, 'staff_skill': 'sales', 'calendar_required': true},
                  'exits': [{'to': 'visiting', 'on': 'calendar.changed', 'if': {'any': [
                    {'exists': ['location']}, {'contains': ['service', 'x']}]}}]
                },
                {
                  'key': 'visiting', 'name': 'Visiting', 'step': 'site_visit',
                  'params': {'duration_min': 45, 'staff_skill': 'survey', 'location_required': true},
                  'exits': [{'to': 'quoting', 'on': 'staff.action', 'if': {'neq': ['source', 'manual']}}]
                },
                {
                  'key': 'quoting', 'name': 'Quoting', 'step': 'quote',
                  'params': {'validity_days': 3, 'discount_limit_pct': 10}, 'approval': true,
                  'exits': [
                    {'to': 'paying', 'on': 'button.clicked'},
                    {'to': 'negotiating', 'on': 'button.clicked', 'if': {'not_in': ['band', ['cold']]}}
                  ]
                },
                {
                  'key': 'paying', 'name': 'Paying', 'step': 'payment',
                  'params': {'kind': 'advance', 'advance_pct': 25, 'link_expiry_hours': 12},
                  'exits': [
                    {'to': 'invoicing', 'on': 'payment.captured', 'if': {'gt': ['budget', 100000]}},
                    {'to': 'quoting', 'on': 'payment.link_expired'}
                  ]
                },
                {'key': 'invoicing', 'name': 'Invoicing', 'step': 'invoice', 'params': {'on': 'payment_captured'},
                 'exits': [{'to': 'booking', 'on': 'timer.fired'}]},
                {'key': 'booking', 'name': 'Booking', 'step': 'schedule', 'params': {'duration_min': 60, 'buffer_min': 0},
                 'exits': [{'to': 'assigning', 'on': 'staff.action', 'if': {'lt': ['budget', 900000]}}]},
                {'key': 'assigning', 'name': 'Assigning', 'step': 'assign',
                 'params': {'strategy': 'skill_match', 'required_skill': 'camera'},
                 'exits': [{'to': 'working', 'on': 'staff.action', 'if': {'lte': ['score', 100]}}]},
                {'key': 'working', 'name': 'Working', 'step': 'job', 'params': {'overdue_after_hours': 4},
                 'exits': [{'to': 'grouping', 'on': 'staff.action'}]},
                {'key': 'grouping', 'name': 'Grouping', 'step': 'batch_assign', 'params': {'capacity': 20},
                 'exits': [{'to': 'delivering', 'on': 'staff.action'}]},
                {'key': 'delivering', 'name': 'Delivering', 'step': 'delivery', 'params': {'requires_approval': true},
                 'exits': [{'to': 'settling', 'on': 'staff.action'}]},
                {'key': 'settling', 'name': 'Settling', 'step': 'payment', 'params': {'kind': 'balance', 'link_expiry_hours': 48},
                 'exits': [{'to': 'reviewing', 'on': 'payment.captured'}]},
                {'key': 'reviewing', 'name': 'Reviewing', 'step': 'review_request', 'params': {'delay_hours': 0},
                 'exits': [{'to': 'completed', 'on': 'timer.fired'}]},
                {'key': 'completed', 'name': 'Done', 'step': 'close', 'params': {'send_purchase_event': true}},
                {'key': 'negotiating', 'exits': [{'to': 'quoting', 'on': 'staff.action'}]},
                {'key': 'junk', 'exits': [{'to': 'asking', 'on': 'staff.action'}]},
                {'key': 'lost'},
                {'key': 'cancelled'},
                {'key': 'needs_human'}
              ],
              'filter_rules': [{'id': 'too_small', 'if': {'lt': ['budget', 500000]}, 'then': 'junk', 'reason': 'Below the test minimum'}],
              'step_conditions': [
                {'step': 'site_visit', 'enabled_if': {'gt': ['budget', 5000000]}},
                {'step': 'payment.advance', 'skip_if': {'eq': ['is_repeat_customer', true]}}
              ]
            }""");

    @Test
    void theSmallestAndTheFullestConfigsPass() {
        assertThat(problems(MINIMAL)).isEmpty();
        assertThat(problems(FULL)).isEmpty();
    }

    @Test
    void theExampleInWorkflowSpecSection15Passes() throws IOException {
        String spec = Files.readString(Path.of("..", "docs", "workflow-spec.md"));
        String section = spec.substring(spec.indexOf("## 15. Workflow configuration"));
        int start = section.indexOf("```json\n") + "```json\n".length();
        String example = section.substring(start, section.indexOf("```", start));

        assertThat(problems(example, "shoot_production")).isEmpty();
    }

    static Stream<Arguments> oneRefusalPerCode() {
        return Stream.of(
                refusal(ProblemCode.JSON_INVALID, "{\"schema_version\": 1,", "$ json_invalid"),
                refusal(
                        ProblemCode.JSON_MEMBER_DUPLICATE,
                        stages("{'key': 'new', 'name': 'New', 'name': 'Again'}, {'key': 'needs_human'}"),
                        "$.stages[0].name json_member_duplicate"),
                refusal(
                        ProblemCode.CONFIG_TOO_LARGE,
                        "{\"pad\": \"" + "a".repeat(ConfigJson.MAX_BYTES) + "\"}",
                        "$ config_too_large"),
                refusal(ProblemCode.CONFIG_NOT_OBJECT, "[1, 2]", "$ config_not_object"),
                refusal(
                        ProblemCode.SCHEMA_VERSION_UNSUPPORTED,
                        MINIMAL.replace("\"schema_version\": 1", "\"schema_version\": 2"),
                        "$.schema_version schema_version_unsupported"),
                refusal(
                        ProblemCode.FIELD_REQUIRED,
                        json("{'stages': [{'key': 'new', 'name': 'New'}, {'key': 'needs_human'}]}"),
                        "$.schema_version field_required"),
                refusal(ProblemCode.FIELD_UNKNOWN, top("'colour': 'red'"), "$.colour field_unknown"),
                refusal(ProblemCode.FIELD_SERVER_SET, top("'version': 3"), "$.version field_server_set"),
                refusal(ProblemCode.TYPE_INVALID, top("'approval_mode': 'yes'"), "$.approval_mode type_invalid"),
                refusal(
                        ProblemCode.VALUE_INVALID,
                        top("'quiet_hours': {'start': '25:00', 'end': '09:00'}"),
                        "$.quiet_hours.start value_invalid"),
                refusal(
                        ProblemCode.STAGE_NEW_MISSING,
                        json("{'schema_version': 1, 'stages': [{'key': 'needs_human'}]}"),
                        "$.stages stage_new_missing"),
                refusal(
                        ProblemCode.STAGE_NEEDS_HUMAN_MISSING,
                        json("{'schema_version': 1, 'stages': [{'key': 'new', 'name': 'New'}]}"),
                        "$.stages stage_needs_human_missing"),
                refusal(
                        ProblemCode.STAGE_KEY_DUPLICATE,
                        stages(
                                "{'key': 'new', 'name': 'New'}, {'key': 'needs_human'}, {'key': 'new', 'name': 'Again'}"),
                        "$.stages[2].key stage_key_duplicate"),
                refusal(
                        ProblemCode.STAGE_UNKNOWN,
                        newStage("'exits': [{'to': 'nowhere', 'on': 'message.received'}]"),
                        "$.stages[0].exits[0].to stage_unknown"),
                refusal(
                        ProblemCode.STAGE_UNREACHABLE,
                        stages(
                                "{'key': 'new', 'name': 'New'}, {'key': 'needs_human'}, {'key': 'island', 'name': 'Island'}"),
                        "$.stages[2] stage_unreachable"),
                refusal(ProblemCode.STEP_UNKNOWN, newStage("'step': 'dance'"), "$.stages[0].step step_unknown"),
                refusal(
                        ProblemCode.EVENT_UNKNOWN,
                        newStage("'exits': [{'to': 'needs_human', 'on': 'action.succeeded'}]"),
                        "$.stages[0].exits[0].on event_unknown"),
                refusal(
                        ProblemCode.EVENT_NOT_ALLOWED,
                        newStage("'exits': [{'to': 'needs_human', 'on': 'optout.received'}]"),
                        "$.stages[0].exits[0].on event_not_allowed"),
                refusal(
                        ProblemCode.ACTION_UNKNOWN,
                        newStage("'exits': [{'to': 'needs_human', 'on': 'staff.action', 'action': 'send_quote'}]"),
                        "$.stages[0].exits[0].action action_unknown"),
                refusal(ProblemCode.TEMPLATE_UNKNOWN, top("'template': 'bakery'"), "$.template template_unknown"),
                refusal(
                        ProblemCode.TIMER_TYPE_UNKNOWN,
                        newStage("'timers': [{'type': 'reminder', 'after_hours': [1], 'max_attempts': 1}]"),
                        "$.stages[0].timers[0].type timer_type_unknown"),
                refusal(
                        ProblemCode.TIMER_DUPLICATE,
                        newStage("'timers': [{'type': 'follow_up', 'after_hours': [1], 'max_attempts': 1},"
                                + " {'type': 'follow_up', 'after_hours': [2], 'max_attempts': 1}]"),
                        "$.stages[0].timers[1].type timer_duplicate"),
                refusal(
                        ProblemCode.CONDITION_OPERATOR_UNKNOWN,
                        exitIf("{'between': ['budget', 1, 2]}"),
                        "$.stages[0].exits[0].if.between condition_operator_unknown"),
                refusal(
                        ProblemCode.CONDITION_FIELD_UNKNOWN,
                        exitIf("{'eq': ['colour', 'red']}"),
                        "$.stages[0].exits[0].if.eq[0] condition_field_unknown"),
                refusal(
                        ProblemCode.CONDITION_INVALID,
                        exitIf("{'lt': ['budget', 1500000.5]}"),
                        "$.stages[0].exits[0].if.lt[1] condition_invalid"),
                refusal(
                        ProblemCode.FILTER_RULE_ID_DUPLICATE,
                        json("""
                                {'schema_version': 1,
                                 'stages': [{'key': 'new', 'name': 'New', 'step': 'score'}, {'key': 'needs_human'}, {'key': 'junk'}],
                                 'filter_rules': [
                                   {'id': 'low', 'if': {'lt': ['budget', 100]}, 'then': 'junk', 'reason': 'Low'},
                                   {'id': 'low', 'if': {'lt': ['score', 10]}, 'then': 'junk', 'reason': 'Lower'}]}"""),
                        "$.filter_rules[1].id filter_rule_id_duplicate"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oneRefusalPerCode")
    void eachCodeIsReportedAtItsPath(ProblemCode code, String config, String expected) {
        assertThat(problems(config)).containsExactly(expected);
    }

    @Test
    void everyCodeHasItsRefusalTest() {
        Set<ProblemCode> tested = oneRefusalPerCode()
                .map(arguments -> (ProblemCode) arguments.get()[0])
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ProblemCode.class)));
        tested.add(ProblemCode.TOO_MANY_PROBLEMS); // theListStopsAfter500Problems

        assertThat(tested).containsExactlyInAnyOrder(ProblemCode.values());
    }

    @Test
    void theListStopsAfter500Problems() {
        String members =
                IntStream.range(0, 600).mapToObj(i -> "'extra_" + i + "': 1").collect(Collectors.joining(", "));

        List<String> found = problems(top(members));

        assertThat(found).hasSize(Problems.MAX + 1);
        assertThat(found.getFirst()).isEqualTo("$.extra_0 field_unknown");
        assertThat(found.getLast()).isEqualTo("$ too_many_problems");
    }

    @Test
    void everyProblemIsReportedAtOnceInDocumentOrder() {
        String config = json("""
                {
                  'schema_version': 1,
                  'colour': 'red',
                  'stages': [
                    {'key': 'new', 'name': '', 'step': 'dance', 'exits': [{'to': 'nowhere', 'on': 'optout.received'}]},
                    {'key': 'needs_human', 'timers': [{'type': 'follow_up', 'after_hours': [24, 4], 'max_attempts': 0}]},
                    {'key': 'Bad Key', 'name': 'Bad'},
                    {'name': 'No key'}
                  ],
                  'meta_conversion_stage': 'gone'
                }""");

        assertThat(problems(config))
                .containsExactly(
                        "$.colour field_unknown",
                        "$.stages[0].name value_invalid",
                        "$.stages[0].step step_unknown",
                        "$.stages[0].exits[0].to stage_unknown",
                        "$.stages[0].exits[0].on event_not_allowed",
                        "$.stages[1].timers[0].after_hours[1] value_invalid",
                        "$.stages[1].timers[0].max_attempts value_invalid",
                        "$.stages[2].key value_invalid",
                        "$.stages[3].key field_required",
                        "$.meta_conversion_stage stage_unknown");
    }

    @Test
    void reachabilityIsNotCheckedWithoutNew() {
        String config = stages("{'key': 'needs_human'}, {'key': 'island', 'name': 'Island'}");

        assertThat(problems(config)).containsExactly("$.stages stage_new_missing");
    }

    @Test
    void theEngineReachesNeedsHumanLostAndCancelledAndFilterRulesReachJunk() {
        String config = json("""
                {'schema_version': 1,
                 'stages': [
                   {'key': 'new', 'name': 'New', 'exits': [{'to': 'scoring', 'on': 'message.received'}]},
                   {'key': 'scoring', 'name': 'Scoring', 'step': 'score'},
                   {'key': 'needs_human'}, {'key': 'lost'}, {'key': 'cancelled'}, {'key': 'junk'},
                   {'key': 'negotiating'}, {'key': 'completed'}],
                 'filter_rules': [{'id': 'low', 'if': {'lt': ['budget', 100]}, 'then': 'junk', 'reason': 'Low'}]}""");

        assertThat(problems(config))
                .as("Negotiating and Completed need an exit that leads to them")
                .containsExactly("$.stages[6] stage_unreachable", "$.stages[7] stage_unreachable");
    }

    @Test
    void reservedStagesHaveDefaultNamesAndEveryOtherStageNeedsOne() {
        assertThat(ConfigFormatV1.RESERVED_STAGES)
                .containsOnlyKeys("needs_human", "negotiating", "junk", "lost", "cancelled", "completed");
        String unnamed = stages("{'key': 'new'}, {'key': 'needs_human'}");
        String renamed = stages("{'key': 'new', 'name': 'New'}, {'key': 'needs_human', 'name': 'A person'}");

        assertThat(problems(unnamed)).containsExactly("$.stages[0].name field_required");
        assertThat(problems(renamed)).isEmpty();
        assertThat(problems(named("n".repeat(60)))).as("60 characters").isEmpty();
        assertThat(problems(named("ü".repeat(60)))).as("characters, not bytes").isEmpty();
        assertThat(problems(named("n".repeat(61)))).containsExactly("$.stages[0].name value_invalid");
        assertThat(problems(stages("{'key': 'new', 'name': '   '}, {'key': 'needs_human'}")))
                .as("blank")
                .containsExactly("$.stages[0].name value_invalid");
    }

    @Test
    void budgetsInConditionsArePaiseSoIntegers() {
        assertThat(problems(exitIf("{'lt': ['budget', 1500000]}"))).isEmpty();
        assertThat(problems(exitIf("{'in': ['budget', [100, 2.5]]}")))
                .containsExactly("$.stages[0].exits[0].if.in[1][1] condition_invalid");
        assertThat(problems(exitIf("{'eq': ['score', '50']}")))
                .containsExactly("$.stages[0].exits[0].if.eq[1] condition_invalid");
        assertThat(problems(exitIf("{'eq': ['opt_out', 'no']}")))
                .containsExactly("$.stages[0].exits[0].if.eq[1] condition_invalid");
    }

    @Test
    void conditionsHaveOneOperatorAndTheirOwnOperandsAtMostTenDeep() {
        assertThat(problems(exitIf("{}"))).containsExactly("$.stages[0].exits[0].if condition_invalid");
        assertThat(problems(exitIf("{'eq': ['service', 'a'], 'neq': ['service', 'b']}")))
                .containsExactly("$.stages[0].exits[0].if condition_invalid");
        assertThat(problems(exitIf("{'all': []}"))).containsExactly("$.stages[0].exits[0].if.all condition_invalid");
        assertThat(problems(exitIf("{'exists': ['location', 'service']}")))
                .containsExactly("$.stages[0].exits[0].if.exists condition_invalid");
        assertThat(problems(exitIf("{'in': ['band', []]}")))
                .containsExactly("$.stages[0].exits[0].if.in[1] condition_invalid");
        assertThat(problems(exitIf("{'eq': ['requirements.colour', null]}")))
                .containsExactly("$.stages[0].exits[0].if.eq[1] condition_invalid");
        assertThat(problems(exitIf("{'eq': ['requirements.Colour', 'red']}")))
                .containsExactly("$.stages[0].exits[0].if.eq[0] condition_field_unknown");
        assertThat(problems(exitIf("{'within_zone': ['location', 'service_zone']}")))
                .as("no workspace setting exists yet")
                .containsExactly("$.stages[0].exits[0].if.within_zone[1] condition_field_unknown");

        String ten = "{'eq': ['service', 'a']}";
        for (int i = 1; i < 10; i++) ten = "{'not': " + ten + "}";
        assertThat(problems(exitIf(ten))).isEmpty();
        assertThat(problems(exitIf("{'not': " + ten + "}")))
                .containsExactly("$.stages[0].exits[0].if" + ".not".repeat(10) + " condition_invalid");
    }

    @Test
    void allRequiredFieldsBelongsToAnExitOfAStageThatRequiresFields() {
        String inQualify = newStage(
                "'step': 'qualify', 'params': {'required_fields': ['service']},"
                        + " 'exits': [{'to': 'needs_human', 'on': 'ai.fields_extracted', 'if': {'all_required_fields': true}}]");
        String elsewhere = exitIf("{'all_required_fields': true}");
        String notTrue = inQualify.replace("\"all_required_fields\": true", "\"all_required_fields\": false");

        assertThat(problems(inQualify)).isEmpty();
        assertThat(problems(elsewhere))
                .containsExactly("$.stages[0].exits[0].if.all_required_fields condition_invalid");
        assertThat(problems(notTrue)).containsExactly("$.stages[0].exits[0].if.all_required_fields condition_invalid");
    }

    @Test
    void stepParametersAreSection5sListTypedAndInRange() {
        assertThat(problems(newStage("'params': {'max_turns': 3}")))
                .as("parameters belong to a step")
                .containsExactly("$.stages[0].params field_unknown");
        assertThat(problems(newStage("'step': 'job', 'params': {'tasks': ['shoot']}")))
                .as("a shape v1 leaves open")
                .containsExactly("$.stages[0].params.tasks field_unknown");
        assertThat(problems(newStage("'step': 'quote', 'params': {'approval': true}")))
                .as("approval is the stage's")
                .containsExactly("$.stages[0].params.approval field_unknown");
        assertThat(problems(newStage("'step': 'quote', 'params': {'discount_limit_pct': 101}")))
                .containsExactly("$.stages[0].params.discount_limit_pct value_invalid");
        assertThat(problems(newStage("'step': 'qualify', 'params': {'max_turns': 2.0}")))
                .as("integers only")
                .containsExactly("$.stages[0].params.max_turns type_invalid");
        assertThat(problems(
                        newStage("'step': 'qualify', 'params': {'required_fields': ['budget', 'budget', 'colour']}")))
                .containsExactly(
                        "$.stages[0].params.required_fields[1] value_invalid",
                        "$.stages[0].params.required_fields[2] value_invalid");
        assertThat(problems(newStage("'step': 'score', 'params': {'band_thresholds': {'hot': 50, 'warm': 50}}")))
                .as("hot above warm")
                .containsExactly("$.stages[0].params.band_thresholds.hot value_invalid");
        assertThat(problems(newStage("'step': 'assign', 'params': {'strategy': 'random'}")))
                .containsExactly("$.stages[0].params.strategy value_invalid");
        assertThat(problems(newStage("'step': 'dance', 'params': {'anything': 1}")))
                .as("an unknown step's parameters are not checked")
                .containsExactly("$.stages[0].step step_unknown");
    }

    @Test
    void aPaymentNamesItsKindAndOnlyAnAdvanceHasAPercentage() {
        assertThat(problems(newStage("'step': 'payment'"))).containsExactly("$.stages[0].params.kind field_required");
        assertThat(problems(newStage("'step': 'payment', 'params': {'kind': 'advance'}")))
                .containsExactly("$.stages[0].params.advance_pct field_required");
        assertThat(problems(newStage("'step': 'payment', 'params': {'kind': 'full', 'advance_pct': 50}")))
                .containsExactly("$.stages[0].params.advance_pct value_invalid");
        assertThat(problems(newStage("'step': 'payment', 'params': {'kind': 'advance', 'advance_pct': 0}")))
                .containsExactly("$.stages[0].params.advance_pct value_invalid");
    }

    @Test
    void timersWaitLongerEachTimeAndTryAtMostOncePerWait() {
        assertThat(problems(newStage("'timers': [{'type': 'follow_up', 'after_hours': [4, 24], 'max_attempts': 2}]")))
                .isEmpty();
        assertThat(problems(newStage("'timers': [{'type': 'follow_up', 'after_hours': [4, 24], 'max_attempts': 3}]")))
                .containsExactly("$.stages[0].timers[0].max_attempts value_invalid");
        assertThat(problems(newStage("'timers': [{'type': 'follow_up', 'after_hours': [], 'max_attempts': 1}]")))
                .containsExactly("$.stages[0].timers[0].after_hours value_invalid");
        assertThat(problems(newStage("'timers': [{'type': 'follow_up', 'after_hours': [0, 4], 'max_attempts': 1}]")))
                .containsExactly("$.stages[0].timers[0].after_hours[0] value_invalid");
        assertThat(problems(newStage("'timers': [{'type': 'follow_up'}]")))
                .containsExactly(
                        "$.stages[0].timers[0].after_hours field_required",
                        "$.stages[0].timers[0].max_attempts field_required");
    }

    @Test
    void exitsLeaveTheirStage() {
        assertThat(problems(newStage("'exits': [{'to': 'new', 'on': 'staff.action'}]")))
                .containsExactly("$.stages[0].exits[0].to value_invalid");
        assertThat(problems(newStage("'exits': [{}]")))
                .containsExactly("$.stages[0].exits[0].to field_required", "$.stages[0].exits[0].on field_required");
    }

    @Test
    void filterRulesSendToJunkFromAScoreStageWithAReason() {
        String noScore = json("""
                {'schema_version': 1, 'stages': [{'key': 'new', 'name': 'New'}, {'key': 'needs_human'}],
                 'filter_rules': [{'id': 'low', 'if': {'lt': ['budget', 100]}, 'then': 'lost'}]}""");

        assertThat(problems(noScore))
                .containsExactly(
                        "$.filter_rules value_invalid",
                        "$.filter_rules[0].then value_invalid",
                        "$.filter_rules[0].reason field_required");
        assertThat(problems(noScore.replace("\"lost\"", "\"junk\"").replace("}]}", ", \"reason\": \"Low\"}]}")))
                .as("junk must be a declared stage too")
                .containsExactly("$.filter_rules value_invalid", "$.filter_rules[0].then stage_unknown");
    }

    @Test
    void stepConditionsNameAStepInUseAndOneConditionKind() {
        String base = "{'schema_version': 1, 'stages': [{'key': 'new', 'name': 'New', 'step': 'payment',"
                + " 'params': {'kind': 'full'}}, {'key': 'needs_human'}], 'step_conditions': [%s]}";

        assertThat(problems(json(base.formatted("{'step': 'payment.full', 'skip_if': {'exists': ['budget']}}"))))
                .isEmpty();
        assertThat(problems(json(base.formatted("{'step': 'payment.advance', 'skip_if': {'exists': ['budget']}}"))))
                .containsExactly("$.step_conditions[0].step step_unknown");
        assertThat(problems(json(base.formatted("{'step': 'site_visit', 'enabled_if': {'exists': ['budget']}}"))))
                .containsExactly("$.step_conditions[0].step step_unknown");
        assertThat(problems(json(base.formatted("{'step': 'payment'}"))))
                .containsExactly("$.step_conditions[0].enabled_if field_required");
        assertThat(
                        problems(
                                json(
                                        base.formatted(
                                                "{'step': 'payment', 'enabled_if': {'exists': ['budget']}, 'skip_if': {'exists': ['budget']}}"))))
                .containsExactly("$.step_conditions[0] value_invalid");
    }

    @Test
    void aTemplateMustBeTheWorkflowsOwn() {
        assertThat(problems(top("'template': 'agency'"), "agency")).isEmpty();
        assertThat(problems(top("'template': 'agency'"), "blank")).containsExactly("$.template value_invalid");
        assertThat(problems(top("'template': 'agency'"), "dev_seed")).containsExactly("$.template value_invalid");
        assertThat(problems(MINIMAL, "dev_seed")).as("a config may name none").isEmpty();
    }

    @Test
    void quietHoursAreTwoDifferentClockTimes() {
        assertThat(problems(top("'quiet_hours': {'start': '21:00', 'end': '09:00'}")))
                .isEmpty();
        assertThat(problems(top("'quiet_hours': {'start': '21:00', 'end': '21:00'}")))
                .containsExactly("$.quiet_hours.end value_invalid");
        assertThat(problems(top("'quiet_hours': {'start': '9:00'}")))
                .containsExactly("$.quiet_hours.start value_invalid", "$.quiet_hours.end field_required");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"schema_version\": 1, \"stages\": []} {}", // trailing content
                "{\"a\": \"\\u0000\"}", // Postgres refuses U+0000 in jsonb
                "{\"a\": \"\\ud800\"}", // a lone surrogate
                "{\"\\udc00\": 1}",
                "{\"a\": 1e1000000}", // beyond numeric
                "{\"a\": 1e-5000}",
                "{\"a\": 1e99999999999}",
                "{'a': 1}",
                ""
            })
    void textThatIsNotStorableJsonIsRefusedWhole(String text) {
        assertThat(problems(text)).containsExactly("$ json_invalid");
    }

    @Test
    void nullTextIsNotJson() {
        assertThat(ConfigJson.parse(null).refused()).containsExactly(new ConfigProblem("$", "json_invalid"));
    }

    @Test
    void everyRepeatedMemberIsReportedWithItsPath() {
        String text = json("""
                {'schema_version': 1, 'schema_version': 1,
                 'stages': [{'key': 'new', 'name': 'New', 'exits': [{'to': 'needs_human', 'on': 'staff.action', 'on': 'x'}]},
                            {'key': 'needs_human', 'two words': 1, 'two words': 2}]}""");

        assertThat(problems(text))
                .containsExactly(
                        "$.schema_version json_member_duplicate",
                        "$.stages[0].exits[0].on json_member_duplicate",
                        "$.stages[1]['two words'] json_member_duplicate");
    }

    @Test
    void aDraftOfExactly256KbIsStored() {
        String padding = "a".repeat(ConfigJson.MAX_BYTES - "{\"pad\": \"\"}".length());

        assertThat(ConfigJson.parse("{\"pad\": \"" + padding + "\"}").isStorable())
                .isTrue();
        assertThat(ConfigJson.parse("{\"pad\": \"" + padding + "é\"}").refused())
                .as("bytes, not characters")
                .containsExactly(new ConfigProblem("$", "config_too_large"));
    }

    @Test
    void namesThatAreNotPlainAreQuotedInPaths() {
        assertThat(problems(top("'two words': 1, 'it\\u0027s': 2")))
                .containsExactly("$['two words'] field_unknown", "$['it\\'s'] field_unknown");
    }

    @Test
    void theHashIsSha256OfTheStoredText() {
        assertThat(ConfigJson.hash("{}"))
                .isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
                .matches("[0-9a-f]{64}");
    }

    // Helpers.

    private static String named(String name) {
        return stages("{'key': 'new', 'name': '" + name + "'}, {'key': 'needs_human'}");
    }

    private static Arguments refusal(ProblemCode code, String config, String expected) {
        return Arguments.of(code, config, expected);
    }

    /** JSON written with ' for " (none of these configs needs an apostrophe). */
    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    private static String stages(String stages) {
        return json("{'schema_version': 1, 'stages': [" + stages + "]}");
    }

    /** The smallest config, with these members added to the stage new. */
    private static String newStage(String members) {
        return stages("{'key': 'new', 'name': 'New', " + members + "}, {'key': 'needs_human'}");
    }

    /** The smallest config, with these members added to the document. */
    private static String top(String members) {
        return json("{'schema_version': 1, " + members
                + ", 'stages': [{'key': 'new', 'name': 'New'}, {'key': 'needs_human'}]}");
    }

    /** The smallest config, with an exit from new to Needs Human on this condition. */
    private static String exitIf(String condition) {
        return newStage("'exits': [{'to': 'needs_human', 'on': 'staff.action', 'if': " + condition + "}]");
    }

    private static List<String> problems(String text) {
        return problems(text, "shoot_production");
    }

    private static List<String> problems(String text, String workflowTemplate) {
        ConfigJson.Parsed parsed = ConfigJson.parse(text);
        List<ConfigProblem> found =
                parsed.isStorable() ? ConfigValidator.validate(parsed.document(), workflowTemplate) : parsed.refused();
        return found.stream()
                .map(problem -> problem.path() + " " + problem.code())
                .toList();
    }
}
