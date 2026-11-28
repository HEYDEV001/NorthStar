package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.workflow.ConfigProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Northstar's default workflow templates (step 3.2; workflow-spec.md section 14), without a database: every revision
 * passes format v1, so none can be handed out unpublishable, and revision 1 of {@code shoot_production} holds exactly
 * the defaults the spec states. A merged revision is never edited, so the content test below never changes; a new
 * revision gets its own.
 */
class DefaultTemplatesTests {

    private static final String SHOOT_PRODUCTION = "shoot_production";

    /**
     * Revision 1, value by value, with where each comes from. Anything the spec states no value for (question wording,
     * max_turns, language, tone, filter rules and the Junk stage, catalogue, crew skills, message text) is absent, and so
     * are the stages format v1 cannot reach yet (workflow-spec.md section 14's growth table).
     */
    private static final String REVISION_1 = """
            {
              "schema_version": 1,
              "template": "shoot_production",
              "approval_mode": true,
              "quiet_hours": {"start": "21:00", "end": "09:00"},
              "stages": [
                {"key": "new", "name": "New", "approval": false,
                 "exits": [{"to": "qualifying", "on": "message.received"}]},
                {"key": "qualifying", "name": "Qualifying", "step": "qualify", "approval": false,
                 "params": {"required_fields": ["service", "date", "time", "location", "budget"]},
                 "timers": [{"type": "follow_up", "after_hours": [4, 24], "max_attempts": 2}],
                 "exits": [{"to": "scored", "on": "ai.fields_extracted", "if": {"all_required_fields": true}}]},
                {"key": "scored", "name": "Scored", "step": "score", "approval": false,
                 "params": {"band_thresholds": {"hot": 75, "warm": 50}, "meta_lead_event": true}},
                {"key": "needs_human"},
                {"key": "lost"}
              ]
            }""";

    @Test
    void everyRevisionOfEveryTemplatePassesFormatV1() {
        assertThat(DefaultTemplates.REVISIONS).as("templates with a default").containsOnlyKeys(SHOOT_PRODUCTION);
        assertThat(ConfigFormatV1.TEMPLATES).containsAll(DefaultTemplates.REVISIONS.keySet());
        for (Map.Entry<String, Integer> template : DefaultTemplates.REVISIONS.entrySet()) {
            for (int revision = 1; revision <= template.getValue(); revision++) {
                String text = DefaultTemplates.read(template.getKey(), revision);
                assertThat(problems(text, template.getKey()))
                        .as(DefaultTemplates.path(template.getKey(), revision))
                        .isEmpty();
            }
        }
    }

    @Test
    void shootProductionRevision1HoldsOnlyTheDefaultsTheSpecStates() {
        assertThat(DefaultTemplates.REVISIONS).containsEntry(SHOOT_PRODUCTION, 1);

        JsonNode revision = ConfigJson.stored(DefaultTemplates.read(SHOOT_PRODUCTION, 1));

        assertThat(revision).isEqualTo(ConfigJson.stored(REVISION_1));
        List<String> keys = new ArrayList<>();
        JsonNode stages = revision.get("stages");
        for (int i = 0; i < stages.size(); i++)
            keys.add(stages.get(i).get("key").stringValue());
        assertThat(keys)
                .as("New → Qualifying → Scored, and the engine's Needs Human and Lost")
                .containsExactly("new", "qualifying", "scored", "needs_human", "lost");
    }

    @Test
    void aCopyBelongsToAShootProductionWorkflowOnly() {
        String text = DefaultTemplates.read(SHOOT_PRODUCTION, 1);

        assertThat(problems(text, "agency")).containsExactly(new ConfigProblem("$.template", "value_invalid"));
    }

    @Test
    void onlyShootProductionStartsWithADraft() {
        DefaultTemplates templates = new DefaultTemplates();

        assertThat(templates.current(SHOOT_PRODUCTION)).contains(DefaultTemplates.read(SHOOT_PRODUCTION, 1));
        for (String other : List.of("agency", "ac_home_service", "coaching", "blank", "dev_seed")) {
            assertThat(templates.current(other)).as(other).isEmpty();
        }
        assertThat(templates.current(null)).isEmpty();
    }

    @Test
    void aRevisionThatFailsFormatV1OrIsMissingStopsTheApp() {
        // A test resource only: the app loads the templates in DefaultTemplates.REVISIONS and nothing else.
        assertThatThrownBy(() -> DefaultTemplates.read("test_refused", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow-templates/test_refused/1.json")
                .hasMessageContaining("stage_needs_human_missing");
        assertThatThrownBy(() -> DefaultTemplates.read(SHOOT_PRODUCTION, 99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing workflow template revision workflow-templates/shoot_production/99.json");
    }

    private static List<ConfigProblem> problems(String text, String workflowTemplate) {
        ConfigJson.Parsed parsed = ConfigJson.parse(text);
        assertThat(parsed.refused()).isEmpty();
        return ConfigValidator.validate(parsed.document(), workflowTemplate);
    }
}
