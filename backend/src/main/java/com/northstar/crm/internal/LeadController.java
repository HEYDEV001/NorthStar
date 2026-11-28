package com.northstar.crm.internal;

import com.northstar.api.NotFoundException;
import com.northstar.crm.internal.LeadResponses.LeadResponse;
import com.northstar.crm.internal.LeadResponses.TimelinePageResponse;
import com.northstar.tenancy.IdempotencyKeyHeader;
import com.northstar.workflow.Timeline;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leads (steps 2.10 and 3.3b, build-document.md section 3, Leads; workflow-spec.md section 3): an admin enters, reads
 * and moves every lead of the business, a staff member only their own; another member's lead, an unassigned one (for
 * staff), a deleted one and another tenant's are the same 404 as a missing one (P9, P11).
 *
 * <p>Entering a lead needs an {@value IdempotencyKeyHeader#NAME} header (P21): 201 with the lead and its contact the
 * first time, 200 with the same lead when the same request comes again, 422 {@code idempotency_key_reused} when the key
 * comes with another request. Refusals: 400 {@code phone_invalid} (P4); 403 {@code assignee_not_allowed} (a staff
 * member naming someone else); 409 {@code no_published_workflow}, and {@code contact_exists} when the number's contact
 * has an open lead, with {@code details.leadId} only for a caller who can see that lead (P11); 422
 * {@code assignee_not_active}.
 *
 * <p>A stage move needs one too ({@link LeadStageMoves}): 200 with the lead after the move, and with the lead as it is
 * now for a repeat. The timeline is newest first and paged by an opaque cursor: {@code limit} 1 to
 * {@value Timeline#MAX_PAGE} (50 by default), {@code cursor} the previous page's {@code nextCursor}.
 */
@RestController
class LeadController {

    private static final String DEFAULT_LIMIT = "50";

    private final ManualLeadEntry entry;
    private final LeadStageMoves moves;
    private final LeadReads reads;

    LeadController(ManualLeadEntry entry, LeadStageMoves moves, LeadReads reads) {
        this.entry = entry;
        this.moves = moves;
        this.reads = reads;
    }

    @PostMapping("/leads")
    ResponseEntity<LeadResponse> create(
            @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
            @Valid @RequestBody CreateLeadRequest request) {
        String key = IdempotencyKeyHeader.require(idempotencyKey, "Entering a lead");
        ManualLeadEntry.Entered entered = entry.create(request, key);
        return ResponseEntity.status(entered.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(entered.lead());
    }

    @GetMapping("/leads/{leadId}")
    LeadResponse lead(@PathVariable UUID leadId) {
        return reads.lead(leadId).orElseThrow(() -> new NotFoundException("lead", leadId));
    }

    @PostMapping("/leads/{leadId}/stage")
    LeadResponse move(
            @PathVariable UUID leadId,
            @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
            @Valid @RequestBody MoveStageRequest request) {
        return moves.move(leadId, request, idempotencyKey).lead();
    }

    @GetMapping("/leads/{leadId}/timeline")
    TimelinePageResponse timeline(
            @PathVariable UUID leadId,
            @RequestParam(required = false) @Size(min = 1, max = 200) String cursor,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(Timeline.MAX_PAGE) int limit) {
        return reads.timeline(leadId, TimelineCursor.decode(leadId, cursor), limit)
                .orElseThrow(() -> new NotFoundException("lead", leadId));
    }
}
