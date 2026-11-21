package com.northstar.integrations.internal;

import com.northstar.integrations.WhatsAppSync;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link WhatsAppSync} and the scheduled syncs (step 2.8a): each creates one {@value WhatsAppSync#SYNC_TYPE} action,
 * keyed by its trigger and its time block, so a repeat in the same block finds the action instead of creating another
 * (P1). Its payload names the trigger only. Automatic: a sync messages nobody and needs no approval.
 *
 * <ul>
 *   <li>scheduled: {@code whatsapp-sync:scheduled:<block>}, one per tenant per 6-hour block of UTC (00, 06, 12, 18);
 *   <li>requested: {@code whatsapp-sync:requested:<block>}, one per tenant per 10-minute block.
 * </ul>
 */
@Service
class WhatsAppSyncRequests implements WhatsAppSync {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSyncRequests.class);

    static final String KEY_PREFIX = "whatsapp-sync:";

    /** Scheduled syncs: one per tenant per block of this length. */
    static final Duration SCHEDULED_EVERY = Duration.ofHours(6);

    /** Requested syncs: requests in the same block of this length share one action. */
    static final Duration REQUESTS_SHARE = Duration.ofMinutes(10);

    private final Actions actions;

    WhatsAppSyncRequests(Actions actions) {
        this.actions = actions;
    }

    @Override
    public ActionView request() {
        return request(Instant.now());
    }

    /** {@link #request()} at a given time, for tests at a block's edges. */
    ActionView request(Instant now) {
        return create("requested", block(now, REQUESTS_SHARE));
    }

    /** The scheduled sync for this 6-hour block ({@link #block}). */
    ActionView requestScheduled(long block) {
        return create("scheduled", block);
    }

    /** Which block of this length the instant falls in, counted from the epoch in UTC. */
    static long block(Instant at, Duration length) {
        return Math.floorDiv(at.getEpochSecond(), length.toSeconds());
    }

    private ActionView create(String trigger, long block) {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("WhatsApp syncs are requested by the system actor");
        Actions.Created created = actions.create(new NewAction(
                SYNC_TYPE,
                KEY_PREFIX + trigger + ":" + block,
                ActionMode.AUTOMATIC,
                false,
                "{\"trigger\": \"" + trigger + "\"}",
                null,
                null,
                null));
        log.info(
                "whatsapp.sync.requested action_id={} trigger={} created={} status={}",
                created.action().id(),
                trigger,
                created.created(),
                created.action().status());
        return created.action();
    }
}
