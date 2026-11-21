-- Step 2.8a: the allowed values of whatsapp_accounts.quality_rating and messaging_limit, unchecked text since 2.1a
-- (data-model.md section 9, integration-specs.md section 2, Quality and limits; Meta's pages checked 21 and 22 Nov 2026).
-- Lowercase versions of Meta's values; null means not read from Meta yet.
--
-- quality_rating: the number's quality rating as the phone number node reports it (GREEN, YELLOW, RED; NA and UNKNOWN
-- both become 'unknown'). No webhook carries it any more, so only the sync writes it.
--
-- messaging_limit: the business portfolio's messaging limit, shared by all its numbers, as Meta's
-- max_daily_conversations_per_business and whatsapp_business_manager_messaging_limit report it. 'tier_not_set' is
-- Meta's TIER_NOT_SET, "the number hasn't sent a message yet".
--
-- Nothing has written either column before this step (onboarding is Phase 8), so no row needs changing first.
alter table northstar.whatsapp_accounts
  add constraint whatsapp_accounts_quality_rating_valid
    check (quality_rating in ('green', 'yellow', 'red', 'unknown')),
  add constraint whatsapp_accounts_messaging_limit_valid
    check (messaging_limit in ('tier_50', 'tier_250', 'tier_2k', 'tier_10k', 'tier_100k', 'tier_unlimited',
                               'tier_not_set'));
