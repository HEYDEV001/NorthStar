package com.northstar.workflow.internal;

import com.northstar.fakes.FakeClock;
import com.northstar.fakes.FakeProvider;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionRecipient;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only (step 1.11): the fake provider and three action types over it, picked up by every test context. test.fake
 * allows 5 seconds per call; test.fake.quick allows 300 ms, for calls that hang past the timeout; test.fake.contact
 * (step 2.5) sends something to a contact, so it names its recipient and an opt-out refuses it. Every one reports its
 * closings to the {@link ClosedActionRecorder} (step 2.6a).
 */
@Configuration(proxyBeanMethods = false)
class FakeActionHandlers {

    static final String TYPE = "test.fake";
    static final String QUICK_TYPE = "test.fake.quick";
    static final String CONTACT_TYPE = "test.fake.contact";

    @Bean
    FakeClock fakeClock() {
        return new FakeClock(Instant.parse("2026-11-11T09:00:00Z"));
    }

    @Bean
    FakeProvider fakeProvider(FakeClock fakeClock) {
        return new FakeProvider(fakeClock, false);
    }

    @Bean
    ClosedActionRecorder closedActionRecorder() {
        return new ClosedActionRecorder();
    }

    @Bean
    ActionHandler fakeActionHandler(FakeProvider fakeProvider, ClosedActionRecorder closed) {
        return new FakeActionHandler(TYPE, ActionRecipient.NONE, Duration.ofSeconds(5), fakeProvider, closed);
    }

    @Bean
    ActionHandler quickFakeActionHandler(FakeProvider fakeProvider, ClosedActionRecorder closed) {
        return new FakeActionHandler(QUICK_TYPE, ActionRecipient.NONE, Duration.ofMillis(300), fakeProvider, closed);
    }

    @Bean
    ActionHandler contactFakeActionHandler(FakeProvider fakeProvider, ClosedActionRecorder closed) {
        return new FakeActionHandler(
                CONTACT_TYPE, ActionRecipient.CONTACT, Duration.ofSeconds(5), fakeProvider, closed);
    }
}
