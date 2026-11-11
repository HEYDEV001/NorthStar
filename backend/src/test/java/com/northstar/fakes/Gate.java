package com.northstar.fakes;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds a fake call open until the test releases it, so a test can act while a call is provably in flight (for
 * example, deliver the same job again while the first attempt holds its lease).
 */
public final class Gate {

    private final CountDownLatch arrived = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    /** True once a call has reached the gate (waits at most the limit, in real time). */
    public boolean awaitArrival(Duration limit) throws InterruptedException {
        return arrived.await(limit.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void release() {
        released.countDown();
    }

    void pass() throws InterruptedException {
        arrived.countDown();
        released.await();
    }
}
