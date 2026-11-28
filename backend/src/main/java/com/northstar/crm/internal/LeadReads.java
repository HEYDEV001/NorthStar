package com.northstar.crm.internal;

import com.northstar.crm.internal.LeadResponses.LeadResponse;
import com.northstar.crm.internal.LeadResponses.TimelineItemResponse;
import com.northstar.crm.internal.LeadResponses.TimelinePageResponse;
import com.northstar.workflow.Timeline;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The leads API's reads (step 3.3b): a lead, and a page of its timeline, each in its own read-only transaction, as the
 * caller may see them (RLS: a staff member only their own leads). Empty for a lead they cannot see, a deleted one or
 * none, which the controller answers with the same 404 (P9, P11).
 */
@Service
class LeadReads {

    private final LeadViews views;
    private final Timeline timeline;
    private final JsonMapper json;
    private final TransactionTemplate readOnly;

    LeadReads(LeadViews views, Timeline timeline, JsonMapper json, PlatformTransactionManager transactionManager) {
        this.views = views;
        this.timeline = timeline;
        this.json = json;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    Optional<LeadResponse> lead(UUID leadId) {
        return readOnly.execute(status -> views.lead(leadId));
    }

    Optional<TimelinePageResponse> timeline(UUID leadId, Timeline.Position after, int limit) {
        return readOnly.execute(status -> timeline.page(leadId, after, limit)
                .map(page -> new TimelinePageResponse(
                        page.entries().stream()
                                .map(entry -> TimelineItemResponse.of(entry, json))
                                .toList(),
                        TimelineCursor.encode(leadId, page.next()),
                        page.stageNames())));
    }
}
