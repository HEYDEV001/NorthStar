package com.northstar.workflow.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The configs the stage move tests run on (step 3.3), as drafts: publishing writes in the workflow ID and version. */
final class StageMoveConfigs {

    private StageMoveConfigs() {}

    /** The default shoot_production template's revision 1 (step 3.2): New, Qualifying, Scored, Needs Human, Lost. */
    static String revision1() {
        try (InputStream in =
                StageMoveConfigs.class.getResourceAsStream("/workflow-templates/shoot_production/1.json")) {
            if (in == null) throw new IllegalStateException("The template's revision 1 is on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A fuller config that format v1 accepts: every reserved stage, a score stage with a filter rule to Junk, exits on
     * payment events (engine-only for members), exits to Completed and Cancelled, and an exit out of Junk.
     */
    static final String FULL = """
            {"schema_version": 1, "template": "shoot_production",
             "stages": [
               {"key": "new", "name": "New", "exits": [{"to": "qualifying", "on": "message.received"}]},
               {"key": "qualifying", "name": "Qualifying", "step": "qualify",
                "params": {"required_fields": ["service", "budget"]},
                "exits": [{"to": "scored", "on": "ai.fields_extracted", "if": {"all_required_fields": true}}]},
               {"key": "scored", "name": "Scored", "step": "score", "params": {"band_thresholds": {"hot": 75, "warm": 50}},
                "exits": [{"to": "quoted", "on": "staff.action"}]},
               {"key": "quoted", "name": "Quoted", "exits": [{"to": "payment_pending", "on": "button.clicked"},
                                                             {"to": "negotiating", "on": "button.clicked"}]},
               {"key": "negotiating", "exits": [{"to": "quoted", "on": "staff.action"}]},
               {"key": "payment_pending", "name": "Payment pending",
                "exits": [{"to": "booked", "on": "payment.captured"}, {"to": "quoted", "on": "payment.link_expired"},
                          {"to": "quoted", "on": "staff.action"}]},
               {"key": "booked", "name": "Booked",
                "exits": [{"to": "completed", "on": "staff.action"}, {"to": "cancelled", "on": "refund.processed"}]},
               {"key": "completed"},
               {"key": "cancelled"},
               {"key": "junk", "exits": [{"to": "new", "on": "message.received"}]},
               {"key": "lost"},
               {"key": "needs_human"}
             ],
             "filter_rules": [{"id": "min_budget", "if": {"lt": ["budget", 1500000]}, "then": "junk",
                               "reason": "Below minimum budget"}]}""";

    /** A config whose schema_version is one the engine does not know (the database's shape check allows any). */
    static final String SCHEMA_VERSION_2 = """
            {"schema_version": 2, "stages": [{"key": "new", "name": "New",
              "exits": [{"to": "qualifying", "on": "message.received"}]}, {"key": "qualifying", "name": "Qualifying"},
              {"key": "needs_human"}, {"key": "lost"}]}""";
}
