package com.northstar.integrations.internal;

import java.time.Duration;
import java.util.List;

/**
 * When the webhook sweep queues an unprocessed WhatsApp message or status event again (status events since step 2.7),
 * and when it gives up on one (step 2.4a,
 * integration-specs.md section 1). Pure: it decides from two facts the row already holds, whether an attempt has failed
 * (error set) and its age (since received_at, on the database's clock), so webhook_events needs no attempt counter.
 *
 * <ul>
 *   <li>Retry steps at ages 2 min (the grace period: the job queued with the row has its turn first), 10 min, 1 h and
 *       6 h. The step is part of the retry job's ID ({@link ProcessWhatsAppWebhook#retryJobIdFor}), so each step queues
 *       at most one job.
 *   <li>A step is queued only within {@link #CATCH_UP} of reaching it, so a sweep every minute does not ask JobRunr
 *       for a job it already has (each such request is a failed insert and an INFO line). A step missed because the
 *       worker was down for longer is skipped; the next step, or the close, still comes.
 *   <li>A row whose processing has failed (error set) is closed once it is {@link #RETRY_WINDOW} old: processed_at is
 *       set and its last error code kept, so it is never retried again.
 *   <li>A row with no failure recorded (error null: its job was lost, its worker died mid-job, or it was stored before
 *       the processing existed) is never closed. Past the last step and its catch-up window it is queued under the last
 *       step on every sweep, a no-op once that job exists, so a row is never given up on without an attempt.
 * </ul>
 */
final class WebhookRetrySchedule {

    /** How long a stored row waits for the job queued with it before the sweep looks at it: the first retry step. */
    static final Duration GRACE = Duration.ofMinutes(2);

    /** The ages at which a still-unprocessed row is queued again; step n is the n-th of these, counted from 1. */
    static final List<Duration> STEPS =
            List.of(GRACE, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofHours(6));

    /** How long after reaching a step the sweep still queues it. Longer than the sweep's interval, with room to spare. */
    static final Duration CATCH_UP = Duration.ofMinutes(10);

    /** A failed row this old is closed: processed_at set, last error kept. */
    static final Duration RETRY_WINDOW = Duration.ofHours(24);

    /** What the sweep does with one unprocessed row. */
    sealed interface Decision {}

    /** Queue the processing job for this step of the schedule. */
    record Retry(int step) implements Decision {}

    /** Give up: the row has failed and is past the retry window. */
    record Close() implements Decision {}

    /** Nothing now: between steps, or already queued for its current step. */
    record Wait() implements Decision {}

    private WebhookRetrySchedule() {}

    static Decision decide(boolean failed, Duration age) {
        if (age.compareTo(GRACE) < 0) return new Wait();
        if (failed && age.compareTo(RETRY_WINDOW) >= 0) return new Close();
        int step = stepAt(age);
        Duration sinceStep = age.minus(STEPS.get(step - 1));
        if (sinceStep.compareTo(CATCH_UP) < 0) return new Retry(step);
        if (!failed && step == STEPS.size()) return new Retry(step);
        return new Wait();
    }

    /** The latest step this age has reached, from 1; requires age at least {@link #GRACE}. */
    static int stepAt(Duration age) {
        int step = 0;
        for (int i = 0; i < STEPS.size(); i++) {
            if (age.compareTo(STEPS.get(i)) >= 0) step = i + 1;
        }
        if (step == 0) throw new IllegalArgumentException("Inside the grace period");
        return step;
    }
}
