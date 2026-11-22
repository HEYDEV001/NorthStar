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
 * Test-only (steps 2.8a and 2.8b): Meta's phone number node and template listing as the sync reads them.
 *
 * <p>Each number answers what the test set with {@link #answer} (by default GREEN and TIER_250). Each WABA lists the
 * pages the test set with {@link #templates} (by default one empty page), with cursors {@code page-1}, {@code page-2}
 * and so on; a test can run {@link #onTemplatePage} after each page is served (to move a clock past a time budget,
 * say). Scripted calls go through {@link #provider()}, keyed by {@link #key} for a number and {@link #templatesKey} for
 * a page, from the 1.11 kit: delays, client timeouts, gates (a read held open while another sync runs), 429s, 5xx,
 * rejections and auth failures. A read changes nothing at Meta, so "performed but lost" is just a lost answer.
 *
 * <p>Every read lands in {@link #reads()} or {@link #pageReads}, with the tenant scope and token it ran with, so tests
 * can see that each tenant's sync used its own token, numbers and WABAs (P9).
 */
final class FakeWhatsAppAccountReader implements WhatsAppAccountReader {

    /** One read the fake answered. */
    record Read(UUID accountId, UUID tenantId, String phoneNumberId, String token) {}

    private final FakeProvider provider;
    private final Map<String, NumberHealth> answers = new ConcurrentHashMap<>();
    private final List<Read> reads = new CopyOnWriteArrayList<>();
    private final Map<String, List<List<ListedTemplate>>> listings = new ConcurrentHashMap<>();
    private final Map<String, Runnable> pageHooks = new ConcurrentHashMap<>();

    /** One template listing page the fake answered. */
    record PageRead(UUID tenantId, String wabaId, String after, String token) {}

    private final List<PageRead> pageReads = new CopyOnWriteArrayList<>();

    FakeWhatsAppAccountReader(FakeClock clock) {
        this.provider = new FakeProvider(clock, true);
    }

    static String key(String phoneNumberId) {
        return "whatsapp-number:" + phoneNumberId;
    }

    /** The provider's script key for one page (0 is the first) of a WABA's listing. */
    static String templatesKey(String wabaId, int page) {
        return "whatsapp-templates:" + wabaId + ":" + page;
    }

    /** What Meta lists for this WABA from now on, page by page. */
    @SafeVarargs
    final void templates(String wabaId, List<ListedTemplate>... pages) {
        listings.put(wabaId, List.of(pages));
    }

    /** Runs after each page of this WABA is served. */
    void onTemplatePage(String wabaId, Runnable hook) {
        pageHooks.put(wabaId, hook);
    }

    List<PageRead> pageReads(String wabaId) {
        return pageReads.stream().filter(read -> read.wabaId().equals(wabaId)).toList();
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
    public TemplatePageRead listTemplates(String wabaId, String after, Secret businessToken) {
        String token = businessToken.utf8();
        UUID tenant = TenantScope.current().map(TenantContext::tenantId).orElse(null);
        pageReads.add(new PageRead(tenant, wabaId, after, token));
        int page = after == null ? 0 : Integer.parseInt(after.substring("page-".length()));
        ActionOutcome outcome = provider.call(templatesKey(wabaId, page), () -> {});
        if (outcome instanceof ActionOutcome.Failed failed) return new Failed(failed);
        List<List<ListedTemplate>> pages = listings.getOrDefault(wabaId, List.of(List.of()));
        List<ListedTemplate> templates = page < pages.size() ? pages.get(page) : List.of();
        String next = page + 1 < pages.size() ? "page-" + (page + 1) : null;
        pageHooks.getOrDefault(wabaId, () -> {}).run();
        return new TemplatePage(templates, 0, next);
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
