package com.northstar.workflow.internal;

import com.northstar.api.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test sources only (step 1.13): there is no lead API until Phase 3, so P9 and P11 are shown through this one lookup,
 * written the way every by-ID lookup must be (inside the tenant scope, missing is a 404).
 */
@RestController
class LeadTestEndpoints {

    private final LeadRepository leads;
    private final TransactionTemplate readOnly;

    LeadTestEndpoints(LeadRepository leads, PlatformTransactionManager transactionManager) {
        this.leads = leads;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @GetMapping("/test/leads/{leadId}")
    Map<String, Object> lead(@PathVariable UUID leadId) {
        return readOnly.execute(status -> leads.findById(leadId)
                .map(lead -> Map.<String, Object>of("id", lead.getId(), "stage", lead.getStage()))
                .orElseThrow(() -> new NotFoundException("lead", leadId)));
    }
}
