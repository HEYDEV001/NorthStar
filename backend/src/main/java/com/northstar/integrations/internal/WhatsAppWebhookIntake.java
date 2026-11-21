package com.northstar.integrations.internal;

import com.northstar.api.LogContext;
import com.northstar.jobs.Jobs;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.tenancy.WebhookRouting;
import com.northstar.tenancy.WebhookRouting.Parked;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores a verified WhatsApp delivery (step 2.2, integration-specs.md section 1, steps 3 to 5): split into items, route
 * each by phone number ID (or, for a change that names only its business account, by WABA ID: step 2.8a), store each
 * tenant's items in that tenant's own transaction in a webhook context (one row per item, a duplicate stores nothing,
 * P1) and queue one job per stored row after the commit, then park items for unknown numbers and accounts (P16).
 * Nothing is processed here and nothing is sent.
 *
 * <p>Tenants are written one after another, each in its own transaction, as a transaction is bound to one tenant. If any
 * write fails the request fails (500) and Meta retries the whole delivery; items already stored are then duplicates.
 *
 * <p>Logs carry counts, webhook event IDs, unrouted row IDs and tenant IDs only: never the body, a wamid, a phone number
 * ID, a WABA ID or a phone number.
 */
@Service
class WhatsAppWebhookIntake {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookIntake.class);

    static final String PROVIDER = "whatsapp";

    private final WebhookRouting routing;
    private final WebhookEventWriter writer;
    private final Jobs jobs;
    private final UnroutedRateLimiter limiter;
    private final TransactionTemplate transactions;

    WhatsAppWebhookIntake(
            WebhookRouting routing,
            WebhookEventWriter writer,
            Jobs jobs,
            UnroutedRateLimiter limiter,
            PlatformTransactionManager transactionManager) {
        this.routing = routing;
        this.writer = writer;
        this.jobs = jobs;
        this.limiter = limiter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What one delivery did; every count is of items except ignored, which counts ignored parts. */
    record Outcome(
            boolean readable,
            int items,
            int stored,
            int duplicates,
            int parked,
            int parkedDuplicates,
            int dropped,
            int ignored) {}

    /** Takes the body only after its signature has been verified. */
    Outcome receive(byte[] verifiedBody) {
        WhatsAppDelivery.Parsed delivery = WhatsAppDelivery.parse(verifiedBody);
        if (!delivery.readable()) {
            // Signed by Meta but unreadable: nothing is stored, and a 200 stops 7 days of retries of the same bytes.
            log.error("webhook.unreadable provider=whatsapp body_bytes={}", verifiedBody.length);
            return new Outcome(false, 0, 0, 0, 0, 0, 0, 0);
        }

        Map<WhatsAppDelivery.Route, Map<String, List<WhatsAppDelivery.Item>>> byKey = new LinkedHashMap<>();
        for (WhatsAppDelivery.Item item : delivery.items()) {
            byKey.computeIfAbsent(item.route(), route -> new LinkedHashMap<>())
                    .computeIfAbsent(item.routingKey(), key -> new ArrayList<>())
                    .add(item);
        }

        // One transaction per tenant, holding every item of every number and account that tenant owns.
        Map<TenantContext, List<WhatsAppDelivery.Item>> byTenant = new LinkedHashMap<>();
        List<WhatsAppDelivery.Item> unknown = new ArrayList<>();
        byKey.forEach((route, keys) -> {
            Map<String, TenantContext> tenants = route(route, keys.keySet());
            keys.forEach((key, items) -> {
                TenantContext tenant = tenants.get(key);
                if (tenant == null) unknown.addAll(items);
                else
                    byTenant.computeIfAbsent(tenant, context -> new ArrayList<>())
                            .addAll(items);
            });
        });

        int stored = 0;
        int duplicates = 0;
        for (Map.Entry<TenantContext, List<WhatsAppDelivery.Item>> tenant : byTenant.entrySet()) {
            int storedHere = store(tenant.getKey(), tenant.getValue());
            stored += storedHere;
            duplicates += tenant.getValue().size() - storedHere;
        }

        int parked = 0;
        int parkedDuplicates = 0;
        int dropped = 0;
        for (WhatsAppDelivery.Item item : unknown) {
            switch (park(item)) {
                case STORED -> parked++;
                case DUPLICATE -> parkedDuplicates++;
                case RATE_LIMITED -> dropped++;
            }
        }

        Outcome outcome = new Outcome(
                true,
                delivery.items().size(),
                stored,
                duplicates,
                parked,
                parkedDuplicates,
                dropped,
                delivery.ignored());
        log.info(
                "webhook.received provider=whatsapp items={} stored={} duplicates={} parked={} parked_duplicates={}"
                        + " dropped={} ignored={}",
                outcome.items(),
                outcome.stored(),
                outcome.duplicates(),
                outcome.parked(),
                outcome.parkedDuplicates(),
                outcome.dropped(),
                outcome.ignored());
        return outcome;
    }

    /** Routes the keys of one kind, at most {@link WebhookRouting#MAX_ROUTING_KEYS} per call. */
    private Map<String, TenantContext> route(WhatsAppDelivery.Route route, Set<String> keys) {
        Function<Set<String>, Map<String, TenantContext>> lookup = switch (route) {
            case PHONE_NUMBER -> routing::whatsAppTenants;
            case WABA -> routing::whatsAppTenantsByWaba;
        };
        Map<String, TenantContext> tenants = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(keys);
        for (int from = 0; from < all.size(); from += WebhookRouting.MAX_ROUTING_KEYS) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + WebhookRouting.MAX_ROUTING_KEYS));
            tenants.putAll(lookup.apply(new LinkedHashSet<>(chunk)));
        }
        return tenants;
    }

    /** Stores one tenant's items in one transaction; jobs are created after it commits. Returns how many were new. */
    private int store(TenantContext tenant, List<WhatsAppDelivery.Item> items) {
        MDC.put(LogContext.TENANT_ID, tenant.tenantId().toString());
        MDC.put(LogContext.USER_ID, tenant.userId().toString());
        try {
            List<UUID> created = TenantScope.call(
                    tenant,
                    () -> transactions.execute(status -> {
                        List<UUID> ids = new ArrayList<>();
                        for (WhatsAppDelivery.Item item : items) {
                            Optional<UUID> id =
                                    writer.insertIfNew(PROVIDER, item.providerEventId(), item.payloadJson());
                            if (id.isEmpty()) continue;
                            ids.add(id.get());
                            jobs.enqueue(
                                    ProcessWhatsAppWebhook.jobIdFor(id.get()),
                                    new ProcessWhatsAppWebhook(tenant.tenantId(), id.get()));
                        }
                        return ids;
                    }));
            for (UUID id : created) log.info("webhook.stored provider=whatsapp webhook_event_id={}", id);
            return created.size();
        } finally {
            MDC.remove(LogContext.TENANT_ID);
            MDC.remove(LogContext.USER_ID);
        }
    }

    private Parked.Outcome park(WhatsAppDelivery.Item item) {
        if (!limiter.tryAcquire(UnroutedRateLimiter.Reason.UNKNOWN_ACCOUNT)) return Parked.Outcome.RATE_LIMITED;
        Parked parked = routing.parkUnknownAccount(
                PROVIDER, item.providerEventId(), item.routingKey(), item.payloadBytes(), item.payloadJson());
        switch (parked.outcome()) {
            case STORED ->
                // The alert for now (P16); the Northstar admin is notified from Phase 8.
                log.warn(
                        "webhook.unknown_account provider=whatsapp unrouted_id={}",
                        parked.unroutedId().orElseThrow());
            case RATE_LIMITED -> limiter.dropped(UnroutedRateLimiter.Reason.UNKNOWN_ACCOUNT);
            case DUPLICATE -> {}
        }
        return parked.outcome();
    }
}
