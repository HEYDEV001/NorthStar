package com.northstar.workflow.internal;

import com.northstar.fakes.FakeProvider;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.RetryPolicy;
import java.time.Duration;

/** Test-only action handler: a stand-in adapter whose provider is the kit's {@link FakeProvider}. */
final class FakeActionHandler implements ActionHandler {

    private final String type;
    private final Duration timeout;
    private final FakeProvider provider;

    FakeActionHandler(String type, Duration timeout, FakeProvider provider) {
        this.type = type;
        this.timeout = timeout;
        this.provider = provider;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.STANDARD_BACKOFF;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public ActionOutcome execute(ActionRequest request) {
        return provider.call(request);
    }
}
