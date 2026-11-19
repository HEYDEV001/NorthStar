package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.fakes.FakeClock;
import com.northstar.integrations.internal.UnroutedRateLimiter.Reason;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The in-memory limit in front of the unrouted table (step 2.2): per reason, per minute, global per instance. */
@ExtendWith(OutputCaptureExtension.class)
class UnroutedRateLimiterTests {

    private final FakeClock clock = new FakeClock(Instant.parse("2026-11-19T10:00:00Z"));
    private final UnroutedRateLimiter limiter = new UnroutedRateLimiter(30, 120, clock);

    @Test
    void eachReasonAllowsItsLimitAMinuteThenRefills() {
        for (int i = 0; i < 30; i++)
            assertThat(limiter.tryAcquire(Reason.INVALID_SIGNATURE)).isTrue();
        assertThat(limiter.tryAcquire(Reason.INVALID_SIGNATURE)).isFalse();
        // The other reason has its own budget.
        for (int i = 0; i < 120; i++)
            assertThat(limiter.tryAcquire(Reason.UNKNOWN_ACCOUNT)).isTrue();
        assertThat(limiter.tryAcquire(Reason.UNKNOWN_ACCOUNT)).isFalse();

        clock.advance(Duration.ofSeconds(2)); // 30 a minute: one every 2 seconds
        assertThat(limiter.tryAcquire(Reason.INVALID_SIGNATURE)).isTrue();
        assertThat(limiter.tryAcquire(Reason.INVALID_SIGNATURE)).isFalse();

        clock.advance(Duration.ofMinutes(5)); // never more than a minute's worth
        int allowed = 0;
        while (limiter.tryAcquire(Reason.INVALID_SIGNATURE)) allowed++;
        assertThat(allowed).isEqualTo(30);
    }

    @Test
    void dropsAreLoggedOnceThenCountedOnceAMinute(CapturedOutput output) {
        for (int i = 0; i < 30; i++) limiter.tryAcquire(Reason.INVALID_SIGNATURE);
        for (int i = 0; i < 500; i++) limiter.tryAcquire(Reason.INVALID_SIGNATURE);
        assertThat(lines(output)).isEqualTo(1);
        assertThat(output.getAll()).contains("reason=invalid_signature dropped=1 (further drops");

        clock.advance(Duration.ofSeconds(61));
        limiter.dropped(Reason.INVALID_SIGNATURE);
        assertThat(lines(output)).isEqualTo(2);
        assertThat(output.getAll()).contains("reason=invalid_signature dropped=500");
    }

    private static long lines(CapturedOutput output) {
        return output.getAll()
                .lines()
                .filter(l -> l.contains("webhook.unrouted_rate_limited"))
                .count();
    }
}
