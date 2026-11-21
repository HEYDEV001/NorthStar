package com.northstar.integrations.internal;

import com.northstar.fakes.FakeClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only (step 2.6a): every test context sends WhatsApp through the fake, never Meta. Primary, so it still wins once
 * step 2.6b adds the Cloud API adapter.
 */
@Configuration(proxyBeanMethods = false)
class FakeWhatsAppConfiguration {

    @Bean
    @Primary
    FakeWhatsAppSender fakeWhatsAppSender(FakeClock fakeClock) {
        return new FakeWhatsAppSender(fakeClock);
    }
}
