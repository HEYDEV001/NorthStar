package com.northstar.workflow;

import java.util.UUID;

/**
 * The engine could not move a lead because other writers kept changing it between its read and its update, every time
 * it tried (P7, step 3.3: three attempts in one transaction). Retryable: the job that asked runs again later, when the
 * lead has settled. Carries the lead's ID only.
 */
public final class LeadChangedConcurrentlyException extends RuntimeException {

    private final UUID leadId;

    public LeadChangedConcurrentlyException(UUID leadId, int attempts) {
        super("Lead " + leadId + " changed concurrently on each of " + attempts + " attempts");
        this.leadId = leadId;
    }

    public UUID leadId() {
        return leadId;
    }
}
