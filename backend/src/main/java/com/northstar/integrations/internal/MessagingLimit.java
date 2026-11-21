package com.northstar.integrations.internal;

import java.util.Arrays;
import java.util.Optional;

/**
 * The business portfolio's WhatsApp messaging limit, shared by all its numbers, as stored in
 * whatsapp_accounts.messaging_limit (step 2.8a, data-model.md section 9). Meta's values, from the
 * {@code max_daily_conversations_per_business} webhook field and the phone number node's
 * {@code whatsapp_business_manager_messaging_limit} (Meta's messaging-limit and webhook reference pages, checked 22 Nov
 * 2026): TIER_50, TIER_250, TIER_2K, TIER_10K, TIER_100K, TIER_UNLIMITED and TIER_NOT_SET ("the number hasn't sent a
 * message yet"). Any other value, numbers included, is not stored.
 */
enum MessagingLimit {
    TIER_50("TIER_50", "tier_50"),
    TIER_250("TIER_250", "tier_250"),
    TIER_2K("TIER_2K", "tier_2k"),
    TIER_10K("TIER_10K", "tier_10k"),
    TIER_100K("TIER_100K", "tier_100k"),
    TIER_UNLIMITED("TIER_UNLIMITED", "tier_unlimited"),
    TIER_NOT_SET("TIER_NOT_SET", "tier_not_set");

    private final String metaValue;
    private final String databaseValue;

    MessagingLimit(String metaValue, String databaseValue) {
        this.metaValue = metaValue;
        this.databaseValue = databaseValue;
    }

    String databaseValue() {
        return databaseValue;
    }

    /** Meta's value exactly as Meta documents it; empty for anything else. */
    static Optional<MessagingLimit> fromMeta(String value) {
        return Arrays.stream(values())
                .filter(limit -> limit.metaValue.equals(value))
                .findFirst();
    }

    /** The stored value; empty for null (never read from Meta). */
    static Optional<MessagingLimit> fromDatabase(String value) {
        return Arrays.stream(values())
                .filter(limit -> limit.databaseValue.equals(value))
                .findFirst();
    }
}
