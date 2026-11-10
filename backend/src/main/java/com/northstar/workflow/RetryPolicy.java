package com.northstar.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * An action type's retries (workflow-spec.md section 10): a first try, then one retry after each wait in order, so
 * attempts in total = waits + 1. A Retry-After longer than the scheduled wait moves the next attempt later, but never
 * beyond the type's overall window, counted from when the action first became runnable.
 */
public record RetryPolicy(List<Duration> waits, Duration window) {

    /**
     * WhatsApp send and Razorpay link create: a first try, then retries after 30 s, 1 m, 5 m, 15 m and 1 h (6 attempts).
     * The spec gives no separate window, so the window is the schedule itself: 81.5 minutes.
     */
    public static final RetryPolicy STANDARD_BACKOFF = of(
            Duration.ofSeconds(30),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1));

    public RetryPolicy {
        waits = List.copyOf(waits);
        if (waits.stream().anyMatch(wait -> wait.isNegative() || wait.isZero())) {
            throw new IllegalArgumentException("Waits must be positive");
        }
        if (window == null || window.compareTo(waits.stream().reduce(Duration.ZERO, Duration::plus)) < 0) {
            throw new IllegalArgumentException("The window must cover the waits");
        }
    }

    /** A policy whose window is the sum of its waits. */
    public static RetryPolicy of(Duration... waits) {
        List<Duration> list = List.of(waits);
        return new RetryPolicy(list, list.stream().reduce(Duration.ZERO, Duration::plus));
    }

    public int maxAttempts() {
        return waits.size() + 1;
    }

    /**
     * When to try again after the given failed attempt (1-based), or null when the attempts are used up. windowStart is
     * when the action first became runnable.
     */
    public Instant nextAttemptAt(int failedAttempt, Instant now, Instant windowStart, Duration retryAfter) {
        if (failedAttempt >= maxAttempts()) return null;
        Duration wait = waits.get(failedAttempt - 1);
        Instant scheduled = now.plus(wait);
        if (retryAfter == null || retryAfter.compareTo(wait) <= 0) return scheduled;
        Instant requested = now.plus(retryAfter);
        Instant windowEnd = windowStart.plus(window);
        if (requested.isAfter(windowEnd)) return windowEnd.isAfter(scheduled) ? windowEnd : scheduled;
        return requested;
    }
}
