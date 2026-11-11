package com.northstar.fakes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock that moves only when a test moves it. Fakes wait on it instead of sleeping: {@link #awaitUntil} blocks until
 * {@link #advance} passes the deadline, and {@link #awaitWaiting} lets a test know a fake is waiting before it moves
 * time. Drives the fakes only; the action executor stays on system time.
 */
public final class FakeClock extends Clock {

    private Instant now;
    private int waiting;

    public FakeClock(Instant start) {
        this.now = start;
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The fake clock is UTC only");
    }

    public synchronized void advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("Time only moves forward");
        now = now.plus(duration);
        notifyAll();
    }

    /** Blocks until the clock reaches the deadline. Interruptible, like a real wait on a provider. */
    public synchronized void awaitUntil(Instant deadline) throws InterruptedException {
        waiting++;
        notifyAll();
        try {
            while (now.isBefore(deadline)) wait();
        } finally {
            waiting--;
        }
    }

    /** Waits (in real time, at most the limit) until this many callers are blocked in {@link #awaitUntil}. */
    public synchronized boolean awaitWaiting(int callers, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (waiting < callers) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            wait(Math.max(1, left / 1_000_000));
        }
        return true;
    }
}
