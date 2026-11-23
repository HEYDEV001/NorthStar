package com.northstar.workflow;

/**
 * Where a lead came from ({@code leads.origin}, data-model.md section 6). Step 2.4b tags every inbound lead
 * {@code direct} (decided 20 Nov 2026), and a lead a member enters by hand is {@code manual} (step 2.10);
 * {@code meta_ad} and {@code chat_link} come with ad referrals and chat links in Phase 7.
 */
public enum LeadOrigin {
    DIRECT,
    MANUAL;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
