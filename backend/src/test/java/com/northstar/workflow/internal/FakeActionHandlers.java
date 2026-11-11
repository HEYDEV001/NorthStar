package com.northstar.workflow.internal;

import com.northstar.fakes.FakeClock;
import com.northstar.fakes.FakeProvider;
import com.northstar.workflow.ActionHandler;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only (step 1.11): the fake provider and two action types over it, picked up by every test context. test.fake
 * allows 5 seconds per call; test.fake.quick allows 300 ms, for calls that hang past the timeout.
 */
@Configuration(proxyBeanMethods = false)
class FakeActionHandlers {

    static final String TYPE = "test.fake";
    static final String QUICK_TYPE = "test.fake.quick";

    @Bean
    FakeClock fakeClock() {
        return new FakeClock(Instant.parse("2026-11-11T09:00:00Z"));
    }

    @Bean
    FakeProvider fakeProvider(FakeClock fakeClock) {
        return new FakeProvider(fakeClock, false);
    }

    @Bean
    ActionHandler fakeActionHandler(FakeProvider fakeProvider) {
        return new FakeActionHandler(TYPE, Duration.ofSeconds(5), fakeProvider);
    }

    @Bean
    ActionHandler quickFakeActionHandler(FakeProvider fakeProvider) {
        return new FakeActionHandler(QUICK_TYPE, Duration.ofMillis(300), fakeProvider);
    }
}
