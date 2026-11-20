package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.integrations.internal.WebhookRetrySchedule.Close;
import com.northstar.integrations.internal.WebhookRetrySchedule.Decision;
import com.northstar.integrations.internal.WebhookRetrySchedule.Retry;
import com.northstar.integrations.internal.WebhookRetrySchedule.Wait;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The webhook sweep's retry schedule (step 2.4a): steps by age, a catch-up window per step, the close at 24 hours. */
class WebhookRetryScheduleTests {

    private static final boolean FAILED = true;
    private static final boolean NOT_ATTEMPTED = false;

    @Test
    void theScheduleIsTheApprovedOne() {
        assertThat(WebhookRetrySchedule.GRACE).isEqualTo(Duration.ofMinutes(2));
        assertThat(WebhookRetrySchedule.STEPS)
                .containsExactly(
                        Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(6));
        assertThat(WebhookRetrySchedule.CATCH_UP)
                .as("longer than the sweep's interval, so a sweep on time never misses a step")
                .isGreaterThan(UnprocessedWebhookSweeper.INTERVAL.multipliedBy(2));
        assertThat(WebhookRetrySchedule.RETRY_WINDOW).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void insideTheGracePeriodNothingHappens() {
        for (boolean failed : new boolean[] {FAILED, NOT_ATTEMPTED}) {
            assertThat(decide(failed, Duration.ZERO)).isEqualTo(new Wait());
            assertThat(decide(failed, Duration.ofSeconds(119))).isEqualTo(new Wait());
        }
    }

    @Test
    void aFailedRowIsQueuedOncePerStepWithinItsCatchUpWindowThenClosedAt24Hours() {
        assertThat(decide(FAILED, Duration.ofMinutes(2))).isEqualTo(new Retry(1));
        assertThat(decide(FAILED, Duration.ofMinutes(9))).isEqualTo(new Retry(1));
        // At 10 minutes step 2 takes over, though step 1's window has not ended.
        assertThat(decide(FAILED, Duration.ofMinutes(10))).isEqualTo(new Retry(2));
        assertThat(decide(FAILED, Duration.ofSeconds(19 * 60 + 59))).isEqualTo(new Retry(2));
        assertThat(decide(FAILED, Duration.ofMinutes(20))).isEqualTo(new Wait());
        assertThat(decide(FAILED, Duration.ofMinutes(59))).isEqualTo(new Wait());
        assertThat(decide(FAILED, Duration.ofHours(1))).isEqualTo(new Retry(3));
        assertThat(decide(FAILED, Duration.ofMinutes(69))).isEqualTo(new Retry(3));
        assertThat(decide(FAILED, Duration.ofMinutes(70))).isEqualTo(new Wait());
        assertThat(decide(FAILED, Duration.ofHours(6))).isEqualTo(new Retry(4));
        assertThat(decide(FAILED, Duration.ofHours(6).plusMinutes(10))).isEqualTo(new Wait());
        assertThat(decide(FAILED, Duration.ofHours(24).minusSeconds(1))).isEqualTo(new Wait());
        assertThat(decide(FAILED, Duration.ofHours(24))).isEqualTo(new Close());
        assertThat(decide(FAILED, Duration.ofDays(30))).isEqualTo(new Close());
    }

    @Test
    void aRowNeverAttemptedIsNeverClosedAndPastTheLastStepIsAlwaysQueuedUnderIt() {
        assertThat(decide(NOT_ATTEMPTED, Duration.ofMinutes(2))).isEqualTo(new Retry(1));
        assertThat(decide(NOT_ATTEMPTED, Duration.ofMinutes(30)))
                .as("between steps it waits, as a failed row does")
                .isEqualTo(new Wait());
        assertThat(decide(NOT_ATTEMPTED, Duration.ofHours(6).plusMinutes(10))).isEqualTo(new Retry(4));
        assertThat(decide(NOT_ATTEMPTED, Duration.ofHours(24))).isEqualTo(new Retry(4));
        assertThat(decide(NOT_ATTEMPTED, Duration.ofDays(3)))
                .as("a row stored before processing existed still gets an attempt")
                .isEqualTo(new Retry(4));
    }

    @Test
    void theStepIsTheLatestOneReached() {
        assertThat(WebhookRetrySchedule.stepAt(Duration.ofMinutes(2))).isEqualTo(1);
        assertThat(WebhookRetrySchedule.stepAt(Duration.ofMinutes(10))).isEqualTo(2);
        assertThat(WebhookRetrySchedule.stepAt(Duration.ofHours(5))).isEqualTo(3);
        assertThat(WebhookRetrySchedule.stepAt(Duration.ofDays(2))).isEqualTo(4);
        assertThatThrownBy(() -> WebhookRetrySchedule.stepAt(Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachStepHasItsOwnJobIdAndTheFirstJobIsNotOneOfThem() {
        UUID row = UUID.randomUUID();
        assertThat(IntStream.rangeClosed(1, WebhookRetrySchedule.STEPS.size())
                        .mapToObj(step -> ProcessWhatsAppWebhook.retryJobIdFor(row, step))
                        .distinct()
                        .toList())
                .hasSize(WebhookRetrySchedule.STEPS.size())
                .doesNotContain(ProcessWhatsAppWebhook.jobIdFor(row));
        assertThat(ProcessWhatsAppWebhook.retryJobIdFor(row, 1))
                .isEqualTo(ProcessWhatsAppWebhook.retryJobIdFor(row, 1));
    }

    private static Decision decide(boolean failed, Duration age) {
        return WebhookRetrySchedule.decide(failed, age);
    }
}
