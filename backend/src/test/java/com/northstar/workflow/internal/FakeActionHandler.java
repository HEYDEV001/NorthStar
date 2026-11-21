package com.northstar.workflow.internal;

import com.northstar.fakes.FakeProvider;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRecipient;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.ClosedAction;
import com.northstar.workflow.RetryPolicy;
import java.time.Duration;

/** Test-only action handler: a stand-in adapter whose provider is the kit's {@link FakeProvider}. */
final class FakeActionHandler implements ActionHandler {

    private final String type;
    private final ActionRecipient recipient;
    private final Duration timeout;
    private final FakeProvider provider;
    private final ClosedActionRecorder closed;

    FakeActionHandler(String type, Duration timeout, FakeProvider provider) {
        this(type, ActionRecipient.NONE, timeout, provider, new ClosedActionRecorder());
    }

    FakeActionHandler(
            String type,
            ActionRecipient recipient,
            Duration timeout,
            FakeProvider provider,
            ClosedActionRecorder closed) {
        this.type = type;
        this.recipient = recipient;
        this.timeout = timeout;
        this.provider = provider;
        this.closed = closed;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public ActionRecipient recipient() {
        return recipient;
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

    @Override
    public void closed(ClosedAction action) {
        closed.record(action);
    }
}
