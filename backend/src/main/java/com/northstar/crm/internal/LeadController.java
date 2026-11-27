package com.northstar.crm.internal;

import com.northstar.crm.internal.LeadResponses.LeadResponse;
import com.northstar.tenancy.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leads (step 2.10, build-document.md section 3, Leads): an admin or a staff member enters a lead by hand. It needs an
 * {@value IdempotencyKeyHeader#NAME} header (P21): 201 with the lead and its contact the first time, 200 with the same
 * lead when the same request comes again, 422 {@code idempotency_key_reused} when the key comes with another request.
 *
 * <p>Refusals: 400 {@code phone_invalid} (P4); 403 {@code assignee_not_allowed} (a staff member naming someone else);
 * 409 {@code no_published_workflow}, and {@code contact_exists} when the number's contact has an open lead, with
 * {@code details.leadId} only for a caller who can see that lead (P11); 422 {@code assignee_not_active}. Reading and
 * changing leads arrive with step 3.9, so there is no Location header yet.
 */
@RestController
class LeadController {

    private final ManualLeadEntry entry;

    LeadController(ManualLeadEntry entry) {
        this.entry = entry;
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
}
