package com.northstar.integrations.internal;

import com.northstar.fakes.FakeClock;
import com.northstar.fakes.FakeProvider;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionOutcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only (step 2.8a): Meta's phone number node as the sync reads it. Each number answers what the test set with
 * {@link #answer} (by default GREEN and TIER_250); scripted calls go through {@link #provider()}, keyed by {@link #key},
 * from the 1.11 kit: delays, client timeouts, gates (a read held open while another sync runs), 429s, 5xx, rejections
 * and auth failures. A read changes nothing at Meta, so "performed but lost" is just a lost answer.
 *
 * <p>Every read lands in {@link #reads()}, with the tenant scope and token it ran with, so tests can see that each
 * tenant's sync used its own token and numbers (P9).
 */
final class FakeWhatsAppAccountReader implements WhatsAppAccountReader {

    /** One read the fake answered. */
    record Read(UUID accountId, UUID tenantId, String phoneNumberId, String token) {}

    private final FakeProvider provider;
    private final Map<String, NumberHealth> answers = new ConcurrentHashMap<>();
    private final List<Read> reads = new CopyOnWriteArrayList<>();

    FakeWhatsAppAccountReader(FakeClock clock) {
        this.provider = new FakeProvider(clock, true);
    }

    static String key(String phoneNumberId) {
        return "whatsapp-number:" + phoneNumberId;
    }

    FakeProvider provider() {
        return provider;
    }

    /** What Meta reports for this number from now on (null values: absent from the answer). */
    void answer(String phoneNumberId, String qualityRating, String messagingLimit) {
        answers.put(phoneNumberId, new NumberHealth(qualityRating, messagingLimit, false));
    }

    /** As if the pinned Graph API version refused the limit field and the number was read without it. */
    void answerWithoutTheLimitField(String phoneNumberId, String qualityRating) {
        answers.put(phoneNumberId, new NumberHealth(qualityRating, null, true));
    }

    List<Read> reads() {
        return List.copyOf(reads);
    }

    List<Read> reads(String phoneNumberId) {
        return reads.stream()
                .filter(read -> read.phoneNumberId().equals(phoneNumberId))
                .toList();
    }

    @Override
    public NumberRead readNumber(UUID accountId, String phoneNumberId, Secret businessToken) {
        String token = businessToken.utf8();
        UUID tenant = TenantScope.current().map(TenantContext::tenantId).orElse(null);
        reads.add(new Read(accountId, tenant, phoneNumberId, token));
        ActionOutcome outcome = provider.call(key(phoneNumberId), () -> {});
        return switch (outcome) {
            case ActionOutcome.Succeeded succeeded ->
                answers.getOrDefault(phoneNumberId, new NumberHealth("GREEN", "TIER_250", false));
            case ActionOutcome.Failed failed -> new Failed(failed);
        };
    }
}
