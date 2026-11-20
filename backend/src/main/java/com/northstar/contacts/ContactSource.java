package com.northstar.contacts;

/**
 * Where a contact arrived from ({@code contact_sources.source}, data-model.md section 4), the same values as
 * {@code leads.origin}. Only {@code direct} is written so far: step 2.4b tags every inbound contact direct (decided 20
 * Nov 2026); {@code meta_ad} and {@code chat_link} come with their referral and link IDs in Phase 7, and {@code manual}
 * with manual lead entry (step 2.10).
 */
public enum ContactSource {
    DIRECT;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
