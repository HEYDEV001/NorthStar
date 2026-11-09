package com.northstar.tenancy.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits invite-acceptance attempts per signed-in user: at most N in a sliding window (10 per 10 minutes). In memory, so
 * per API instance; a shared limiter is a Phase 9 note. Tokens are 256-bit, so this is abuse control, not brute-force
 * defence.
 */
@Component
class AcceptRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    @Autowired
    AcceptRateLimiter(
            @Value("${northstar.invites.accept-attempts:10}") int maxAttempts,
            @Value("${northstar.invites.accept-window:10m}") Duration window) {
        this(maxAttempts, window, Clock.systemUTC());
    }

    AcceptRateLimiter(int maxAttempts, Duration window, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = clock;
    }

    /** Records an attempt; false when the user has already used up the window. */
    boolean tryAcquire(UUID userId) {
        Instant now = clock.instant();
        Deque<Instant> recent = attempts.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(window))) recent.pollFirst();
            if (recent.size() >= maxAttempts) return false;
            recent.addLast(now);
            return true;
        }
    }
}
