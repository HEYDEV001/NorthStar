package com.northstar.integrations.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first of two limits on writes to ingest.unrouted_webhooks (step 2.2): a token bucket per reason in each API
 * instance, so a flood of junk is turned away before it uses a database connection. The second, a per-minute cap
 * across every instance, is inside the parking functions themselves.
 *
 * <p>Global, not per client IP: on App Platform the client's address comes only from an edge header the app would have
 * to trust, senders rotate addresses, and the goal, a table junk cannot flood, is guaranteed only by a global cap.
 * Over the limit nothing is stored and the caller answers exactly as it would have (401 or 200), so a sender learns
 * nothing. Drops are logged once when they start, then as one count per minute, so the log cannot be flooded either.
 */
final class UnroutedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UnroutedRateLimiter.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    enum Reason {
        INVALID_SIGNATURE("invalid_signature"),
        UNKNOWN_ACCOUNT("unknown_account");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private final Clock clock;
    private final Map<Reason, Bucket> buckets = new EnumMap<>(Reason.class);

    UnroutedRateLimiter(int invalidSignaturePerMinute, int unknownAccountPerMinute, Clock clock) {
        if (invalidSignaturePerMinute < 1 || unknownAccountPerMinute < 1) {
            throw new IllegalStateException("northstar.webhooks.unrouted limits must be at least 1 per minute");
        }
        this.clock = clock;
        Instant now = clock.instant();
        buckets.put(Reason.INVALID_SIGNATURE, new Bucket(invalidSignaturePerMinute, now));
        buckets.put(Reason.UNKNOWN_ACCOUNT, new Bucket(unknownAccountPerMinute, now));
    }

    /** Takes one write from the reason's bucket; false (and counted as dropped) when it is empty. */
    boolean tryAcquire(Reason reason) {
        Bucket bucket = buckets.get(reason);
        synchronized (bucket) {
            Instant now = clock.instant();
            bucket.refill(now);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return true;
            }
        }
        dropped(reason);
        return false;
    }

    /** Counts a write that was not made: over this limit, or over the database's cap. */
    void dropped(Reason reason) {
        Bucket bucket = buckets.get(reason);
        long toReport = 0;
        boolean first = false;
        synchronized (bucket) {
            Instant now = clock.instant();
            bucket.droppedSinceReport++;
            if (bucket.lastReport == null) {
                first = true;
                bucket.lastReport = now;
                toReport = bucket.droppedSinceReport;
                bucket.droppedSinceReport = 0;
            } else if (!now.isBefore(bucket.lastReport.plus(MINUTE))) {
                bucket.lastReport = now;
                toReport = bucket.droppedSinceReport;
                bucket.droppedSinceReport = 0;
            }
        }
        if (first) {
            log.warn(
                    "webhook.unrouted_rate_limited reason={} dropped=1 (further drops are counted once a minute)",
                    reason.code());
        } else if (toReport > 0) {
            log.warn("webhook.unrouted_rate_limited reason={} dropped={}", reason.code(), toReport);
        }
    }

    /** Refills continuously at perMinute per minute, up to perMinute. */
    private static final class Bucket {

        private final int perMinute;
        private double tokens;
        private Instant lastRefill;
        private long droppedSinceReport;
        private Instant lastReport;

        Bucket(int perMinute, Instant now) {
            this.perMinute = perMinute;
            this.tokens = perMinute;
            this.lastRefill = now;
        }

        void refill(Instant now) {
            if (now.isAfter(lastRefill)) {
                double minutes = Duration.between(lastRefill, now).toNanos() / (double) MINUTE.toNanos();
                tokens = Math.min(perMinute, tokens + minutes * perMinute);
                lastRefill = now;
            }
        }
    }
}
