package com.northstar.workflow;

import com.northstar.api.ConflictException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 409 {@code stale_lead} (P7, step 3.3): the change was made against an older version of the lead than the one stored,
 * so it is refused rather than written over what someone else changed. It carries the lead as it now is
 * ({@code details.lead}), so the caller sees the latest values and decides again.
 */
public final class StaleLeadException extends ConflictException {

    private final LeadMoves.LeadStage current;

    public StaleLeadException(LeadMoves.LeadStage current) {
        super("stale_lead", "The lead was changed by someone else; review it and try again", details(current));
        this.current = current;
    }

    /** The lead as it is now. */
    public LeadMoves.LeadStage current() {
        return current;
    }

    private static Map<String, Object> details(LeadMoves.LeadStage lead) {
        Objects.requireNonNull(lead, "lead");
        // Every field always present, null when it has no value; IDs, stage keys, times and the version only.
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", lead.id());
        view.put("workflowVersionId", lead.workflowVersionId());
        view.put("stage", lead.stage());
        view.put("previousStage", lead.previousStage());
        view.put("stageEnteredAt", lead.stageEnteredAt());
        view.put("closedAt", lead.closedAt());
        view.put("version", lead.version());
        return Map.of("lead", view);
    }
}
