package com.northstar.contacts;

/**
 * Where a contact arrived from ({@code contact_sources.source}, data-model.md section 4), the same values as
 * {@code leads.origin}. Step 2.4b tags every inbound contact {@code direct} (decided 20 Nov 2026), and manual lead
 * entry (step 2.10) tags the contact it is for {@code manual}; {@code meta_ad} and {@code chat_link} come with their
 * referral and link IDs in Phase 7.
 */
public enum ContactSource {
    DIRECT,
    MANUAL;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
