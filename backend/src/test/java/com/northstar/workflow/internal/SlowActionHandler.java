package com.northstar.workflow.internal;

import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.RetryPolicy;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.springframework.stereotype.Component;

/** Test-only handler that never answers within its 300 ms timeout; it notes when the executor interrupts it. */
@Component
class SlowActionHandler implements ActionHandler {

    static final String TYPE = "test.slow";

    volatile CountDownLatch interrupted = new CountDownLatch(1);

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.STANDARD_BACKOFF;
    }

    @Override
    public Duration timeout() {
        return Duration.ofMillis(300);
    }

    @Override
    public ActionOutcome execute(ActionRequest request) {
        try {
            Thread.sleep(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            interrupted.countDown();
        }
        return ActionOutcome.succeeded(null);
    }
}
