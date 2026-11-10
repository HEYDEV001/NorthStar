package com.northstar.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The retry table's arithmetic (workflow-spec.md section 10). */
class RetryPolicyTests {

    private static final Instant START = Instant.parse("2026-11-10T10:00:00Z");
    private static final RetryPolicy STANDARD = RetryPolicy.STANDARD_BACKOFF;

    @Test
    void aFirstTryPlusFiveRetriesWithEveryWait() {
        assertThat(STANDARD.maxAttempts()).isEqualTo(6);
        assertThat(STANDARD.window()).isEqualTo(Duration.ofSeconds(4890));
        List<Duration> waits = List.of(
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
                Duration.ofHours(1));
        for (int failed = 1; failed <= 5; failed++) {
            assertThat(STANDARD.nextAttemptAt(failed, START, START, null)).isEqualTo(START.plus(waits.get(failed - 1)));
        }
        assertThat(STANDARD.nextAttemptAt(6, START, START, null))
                .as("attempts used up")
                .isNull();
    }

    @Test
    void retryAfterOnlyEverMovesTheNextAttemptLaterAndStaysInTheWindow() {
        Instant now = START.plusSeconds(60);
        assertThat(STANDARD.nextAttemptAt(1, now, START, Duration.ofSeconds(10)))
                .isEqualTo(now.plusSeconds(30));
        assertThat(STANDARD.nextAttemptAt(1, now, START, Duration.ofMinutes(20)))
                .isEqualTo(now.plus(Duration.ofMinutes(20)));
        assertThat(STANDARD.nextAttemptAt(1, now, START, Duration.ofHours(5))).isEqualTo(START.plusSeconds(4890));
        // Late in the window, a Retry-After cut off at the window never pulls the attempt before the schedule.
        Instant late = START.plusSeconds(4880);
        assertThat(STANDARD.nextAttemptAt(5, late, START, Duration.ofHours(5)))
                .isEqualTo(late.plus(Duration.ofHours(1)));
        assertThat(STANDARD.nextAttemptAt(6, late, START, Duration.ofSeconds(1)))
                .isNull();
    }

    @Test
    void aWindowShorterThanTheWaitsIsRefused() {
        assertThatThrownBy(() -> new RetryPolicy(List.of(Duration.ofHours(2)), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
