package com.northstar.workflow;

/**
 * Where a lead came from ({@code leads.origin}, data-model.md section 6). Only {@code direct} is written so far: step
 * 2.4b tags every inbound lead direct (decided 20 Nov 2026); {@code meta_ad} and {@code chat_link} come with ad
 * referrals and chat links in Phase 7, and {@code manual} with manual lead entry (step 2.10).
 */
public enum LeadOrigin {
    DIRECT;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
