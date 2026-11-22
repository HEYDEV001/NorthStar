package com.northstar.integrations.internal;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.contacts.TemplateStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Meta's template statuses and events as Northstar's (step 2.8b, integration-specs.md section 2). The expected mapping is
 * written out here again, from Meta's references (checked 22 Nov 2026) and the decisions of 22 Nov 2026, rather than read
 * from {@link TemplateStatuses}; and the spec's table must list exactly what the code does.
 */
class TemplateStatusesTests {

    /** Every status Meta lists for a template, and every status event: Meta's value → ours. */
    private static final Map<String, TemplateStatus> STATUSES = Map.ofEntries(
            entry("PENDING", TemplateStatus.SUBMITTED),
            entry("IN_APPEAL", TemplateStatus.SUBMITTED),
            entry("APPROVED", TemplateStatus.APPROVED),
            entry("REINSTATED", TemplateStatus.APPROVED),
            entry("PAUSED", TemplateStatus.PAUSED),
            entry("REJECTED", TemplateStatus.REJECTED),
            entry("LIMIT_EXCEEDED", TemplateStatus.REJECTED),
            entry("DISABLED", TemplateStatus.DISABLED),
            entry("PENDING_DELETION", TemplateStatus.DELETED),
            entry("DELETED", TemplateStatus.DELETED),
            entry("ARCHIVED", TemplateStatus.ARCHIVED));

    @Test
    void everyStatusAndStatusEventMetaDocumentsMapsToOurs() {
        STATUSES.forEach((meta, ours) -> {
            assertThat(TemplateStatuses.forListed(meta)).as(meta).contains(ours);
            assertThat(TemplateStatuses.forEvent(meta)).as(meta).isEqualTo(new TemplateStatuses.SetStatus(ours));
        });
    }

    @Test
    void eventsThatSetNoStatus() {
        assertThat(TemplateStatuses.forEvent("FLAGGED")).isInstanceOf(TemplateStatuses.Flagged.class);
        assertThat(TemplateStatuses.forEvent("LOCKED")).isInstanceOf(TemplateStatuses.Locked.class);
        assertThat(TemplateStatuses.forEvent("UNARCHIVED")).isInstanceOf(TemplateStatuses.Unarchived.class);
        for (String other : java.util.Arrays.asList("UNPAUSED", "UNLOCKED", "approved", "", null)) {
            assertThat(TemplateStatuses.forEvent(other)).as(other).isInstanceOf(TemplateStatuses.Unsupported.class);
        }
        for (String listed : java.util.Arrays.asList("FLAGGED", "UNARCHIVED", "LOCKED", "approved", null)) {
            assertThat(TemplateStatuses.forListed(listed)).as(listed).isEmpty();
        }
    }

    @Test
    void reasonsAndCategoriesAreFixedCodesOrNothing() {
        assertThat(TemplateStatuses.reasonCode("INCORRECT_CATEGORY")).isEqualTo("incorrect_category");
        assertThat(TemplateStatuses.reasonCode("TAG_CONTENT_MISMATCH")).isEqualTo("tag_content_mismatch");
        assertThat(TemplateStatuses.reasonCode("SOMETHING_NEW")).isEqualTo("other");
        assertThat(TemplateStatuses.reasonCode(null)).isEqualTo("none");
        assertThat(TemplateStatuses.category("UTILITY")).contains("UTILITY");
        assertThat(TemplateStatuses.category("AUTHENTICATION")).contains("AUTHENTICATION");
        for (String odd : java.util.Arrays.asList("utility", "Marketing!", "", "A".repeat(33), null)) {
            assertThat(TemplateStatuses.category(odd)).as(odd).isEmpty();
        }
    }

    @Test
    void blockingStatusesAreTheOnesASendCannotGoOutOn() {
        assertThat(List.of(TemplateStatus.values()))
                .filteredOn(TemplateStatuses::blocking)
                .containsExactlyInAnyOrder(
                        TemplateStatus.REJECTED,
                        TemplateStatus.PAUSED,
                        TemplateStatus.DISABLED,
                        TemplateStatus.DELETED,
                        TemplateStatus.ARCHIVED);
    }

    @Test
    void theSpecsTableListsExactlyWhatTheCodeDoes() throws IOException {
        String spec = Files.readString(Path.of("../docs/integration-specs.md"));
        Pattern row = Pattern.compile("^\\| `([A-Z_]+)` \\| `([a-z]+)` \\|.*$");
        Map<String, String> rows = new java.util.TreeMap<>();
        for (String line : spec.lines().toList()) {
            Matcher matcher = row.matcher(line);
            if (matcher.matches()) rows.put(matcher.group(1), matcher.group(2));
        }
        Map<String, String> expected = new java.util.TreeMap<>();
        STATUSES.forEach((meta, ours) -> expected.put(meta, ours.code()));
        assertThat(rows)
                .as("integration-specs.md section 2, Template status from Meta")
                .isEqualTo(expected);
        for (String unchanged : List.of("FLAGGED", "LOCKED", "UNARCHIVED")) {
            assertThat(spec).contains("| `" + unchanged + "` | unchanged |");
        }
    }
}
