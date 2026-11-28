package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The state machine's table (step 3.3, workflow-spec.md section 3) with no database: how a stored config is read, and
 * every move a member may make on the default shoot_production template's revision 1 and on a fuller config.
 */
class StageRulesTests {

    private static final List<String> REVISION_1_STAGES = List.of("new", "qualifying", "scored", "needs_human", "lost");

    @Test
    void theTestConfigsAreConfigsFormatV1Accepts() {
        assertThat(ConfigValidator.validate(ConfigJson.stored(StageMoveConfigs.FULL), "shoot_production"))
                .isEmpty();
        assertThat(ConfigValidator.validate(ConfigJson.stored(StageMoveConfigs.revision1()), "shoot_production"))
                .isEmpty();
    }

    @Test
    void aVersionIsRunOnlyWithASchemaVersionTheEngineKnowsAndAShapeItCanRead() {
        assertThat(WorkflowGraph.parse(StageMoveConfigs.revision1()).graph()).isNotNull();
        Map<String, String> unsupported = new LinkedHashMap<>();
        // The 2.11 dev seed's version 1, from before step 3.1, has no schema_version.
        unsupported.put("{\"stages\": [{\"key\": \"new\", \"name\": \"New\"}]}", "schema_version_missing");
        unsupported.put("{\"schema_version\": 2, \"stages\": []}", "schema_version_unknown");
        unsupported.put("{\"schema_version\": \"1\", \"stages\": []}", "schema_version_unknown");
        unsupported.put("{\"schema_version\": 1.5, \"stages\": []}", "schema_version_unknown");
        unsupported.put("{\"schema_version\": 1}", "config_unreadable");
        unsupported.put("{\"schema_version\": 1, \"stages\": {}}", "config_unreadable");
        unsupported.put("{\"schema_version\": 1, \"stages\": [{\"name\": \"No key\"}]}", "config_unreadable");
        unsupported.put(
                "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\"}, {\"key\": \"new\"}]}", "config_unreadable");
        unsupported.put(
                "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"exits\": [{\"to\": \"x\"}]}]}",
                "config_unreadable");
        unsupported.put(
                "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\"}], \"filter_rules\": [{\"then\": \"junk\"}]}",
                "config_unreadable");
        unsupported.put("[1, 2]", "config_unreadable");
        unsupported.put("not json", "config_unreadable");
        unsupported.forEach((config, reason) -> {
            WorkflowGraph.Parsed parsed = WorkflowGraph.parse(config);
            assertThat(parsed.graph()).as(config).isNull();
            assertThat(parsed.unsupported()).as(config).isEqualTo(reason);
        });
    }

    @Test
    void theGraphReadsStagesExitsTheScoreStageAndJunkRules() {
        WorkflowGraph graph = WorkflowGraph.parse(StageMoveConfigs.FULL).graph();

        assertThat(graph.declares("payment_pending")).isTrue();
        assertThat(graph.declares("contacted")).isFalse();
        assertThat(graph.declares(null)).isFalse();
        assertThat(graph.exits("payment_pending"))
                .containsExactly(
                        new WorkflowGraph.Exit("booked", "payment.captured", false),
                        new WorkflowGraph.Exit("quoted", "payment.link_expired", false),
                        new WorkflowGraph.Exit("quoted", "staff.action", false));
        assertThat(graph.exits("qualifying"))
                .singleElement()
                .satisfies(exit -> assertThat(exit.conditional()).isTrue());
        assertThat(graph.exits("lost")).isEmpty();
        assertThat(graph.exits("contacted")).isEmpty();
        assertThat(graph.isScoreStage("scored")).isTrue();
        assertThat(graph.isScoreStage("qualifying")).isFalse();
        assertThat(graph.isJunkRule("min_budget")).isTrue();
        assertThat(graph.isJunkRule("zone")).isFalse();
        for (String closing : List.of("junk", "lost", "completed", "cancelled")) {
            assertThat(WorkflowGraph.isClosing(closing)).as(closing).isTrue();
        }
        assertThat(WorkflowGraph.isClosing("needs_human")).isFalse();
        assertThat(WorkflowGraph.isClosing("booked")).isFalse();
    }

    /** Every member move between the stages of revision 1, from each stage (Needs Human entered from Qualifying). */
    @Test
    void everyMemberMoveOnTheShootProductionTemplatesRevision1() {
        WorkflowGraph graph = WorkflowGraph.parse(StageMoveConfigs.revision1()).graph();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("new>qualifying", "exit");
        expected.put("new>scored", "transition_not_allowed");
        expected.put("new>needs_human", "needs_human");
        expected.put("new>lost", "lost");
        expected.put("qualifying>new", "transition_not_allowed");
        expected.put("qualifying>scored", "exit");
        expected.put("qualifying>needs_human", "needs_human");
        expected.put("qualifying>lost", "lost");
        expected.put("scored>new", "transition_not_allowed");
        expected.put("scored>qualifying", "transition_not_allowed");
        expected.put("scored>needs_human", "needs_human");
        expected.put("scored>lost", "lost");
        expected.put("needs_human>new", "transition_not_allowed");
        expected.put("needs_human>qualifying", "hand_back");
        expected.put("needs_human>scored", "transition_not_allowed");
        expected.put("needs_human>lost", "lost");
        expected.put("lost>new", "transition_not_allowed");
        expected.put("lost>qualifying", "transition_not_allowed");
        expected.put("lost>scored", "transition_not_allowed");
        expected.put("lost>needs_human", "transition_not_allowed");

        Map<String, String> actual = new LinkedHashMap<>();
        for (String from : REVISION_1_STAGES) {
            for (String to : REVISION_1_STAGES) {
                if (from.equals(to)) continue;
                String previous = from.equals("needs_human") ? "qualifying" : null;
                actual.put(from + ">" + to, outcome(StageRules.forMember(graph, from, previous, to)));
            }
        }
        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    @Test
    void aHandBackGoesOnlyToTheStageNeedsHumanWasEnteredFrom() {
        WorkflowGraph graph = WorkflowGraph.parse(StageMoveConfigs.revision1()).graph();

        assertThat(outcome(StageRules.forMember(graph, "needs_human", "new", "new")))
                .isEqualTo("hand_back");
        assertThat(outcome(StageRules.forMember(graph, "needs_human", "new", "qualifying")))
                .isEqualTo("transition_not_allowed");
        assertThat(outcome(StageRules.forMember(graph, "needs_human", "scored", "scored")))
                .isEqualTo("hand_back");
    }

    @Test
    void onTheFullerConfigRestoresMoneyExitsAndClosingStagesFollowTheRules() {
        WorkflowGraph graph = WorkflowGraph.parse(StageMoveConfigs.FULL).graph();
        Map<String, String> expected = new LinkedHashMap<>();
        // Junk is restored to Qualifying (section 4), and follows its own exit; nothing else leaves it unforced.
        expected.put("junk>qualifying", "restore");
        expected.put("junk>new", "exit");
        expected.put("junk>scored", "transition_not_allowed");
        expected.put("junk>needs_human", "transition_not_allowed");
        expected.put("junk>lost", "transition_not_allowed");
        // Exits only a verified payment or refund event moves; a staff exit beside one is a member's to follow.
        expected.put("payment_pending>booked", "transition_engine_only");
        expected.put("payment_pending>quoted", "exit");
        expected.put("booked>cancelled", "transition_engine_only");
        expected.put("booked>completed", "exit");
        // Lost and Needs Human from any open stage; Cancelled only along an exit (until step 3.5).
        expected.put("booked>lost", "lost");
        expected.put("booked>needs_human", "needs_human");
        expected.put("quoted>cancelled", "transition_not_allowed");
        expected.put("quoted>negotiating", "exit");
        expected.put("negotiating>quoted", "exit");
        // Filter rules are the engine's: a member does not send a lead to Junk.
        expected.put("scored>junk", "transition_not_allowed");
        // Closing stages close for good, but by an admin's force.
        expected.put("completed>booked", "transition_not_allowed");
        expected.put("cancelled>booked", "transition_not_allowed");
        expected.put("lost>new", "transition_not_allowed");
        // A lead whose own stage the version does not declare moves only by force.
        expected.put("contacted>new", "transition_not_allowed");
        expected.put("contacted>lost", "transition_not_allowed");

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(move -> {
            String[] stages = move.split(">");
            actual.put(move, outcome(StageRules.forMember(graph, stages[0], null, stages[1])));
        });
        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    private static String outcome(StageRules.Decision decision) {
        return switch (decision) {
            case StageRules.Allowed allowed -> allowed.kind().code();
            case StageRules.Refused refused -> refused.code();
        };
    }
}
