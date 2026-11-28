package com.northstar.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A lead's history ({@code timeline_events}, data-model.md section 10; step 3.3), newest first. Rows hold IDs, stage
 * keys, fixed codes, numbers and times only, never text. Only the workflow module writes them; other modules will do so
 * through typed methods here, one per row type, as each type is built.
 *
 * <p>Every method runs only inside the caller's transaction, in the current tenant, with RLS: a staff member reads the
 * timeline of their own leads only.
 */
public interface Timeline {

    int MAX_PAGE = 100;

    /**
     * Up to {@code limit} rows (1 to {@value #MAX_PAGE}) of the lead's timeline, newest first, after {@code after}
     * (null: from the newest). Empty when the caller cannot see the lead, it is deleted, or there is none.
     */
    Optional<Page> page(UUID leadId, Position after, int limit);

    /** Where a page ends: the last row's time (to the microsecond, as stored) and ID, which breaks ties. */
    record Position(Instant occurredAt, UUID id) {

        public Position {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * A page of rows, where the next one starts (null when this is the last), and the names of the stages of the lead's
     * own version, key to name in the version's order (step 3.3b), for showing the rows' stage keys; empty for a version
     * the engine cannot run.
     */
    record Page(List<Entry> entries, Position next, Map<String, String> stageNames) {

        public Page {
            entries = List.copyOf(entries);
            stageNames = Collections.unmodifiableMap(new LinkedHashMap<>(stageNames));
        }
    }

    /**
     * One row. actorMembershipId is set for a staff actor only; refTable and refId together or not at all; data is the
     * stored JSON object, as text.
     */
    record Entry(
            UUID id,
            String type,
            String actorType,
            UUID actorMembershipId,
            String refTable,
            UUID refId,
            String summary,
            String data,
            Instant occurredAt) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(actorType, "actorType");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
