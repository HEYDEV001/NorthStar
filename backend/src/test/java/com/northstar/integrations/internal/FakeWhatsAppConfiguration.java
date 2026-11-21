package com.northstar.integrations.internal;

import com.northstar.fakes.FakeClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only (step 2.6a): every test context sends WhatsApp through the fake, never Meta. Primary, so it wins over the
 * Cloud API adapter (step 2.6b), which tests reach only against a loopback stand-in.
 */
@Configuration(proxyBeanMethods = false)
class FakeWhatsAppConfiguration {

    @Bean
    @Primary
    FakeWhatsAppSender fakeWhatsAppSender(FakeClock fakeClock) {
        return new FakeWhatsAppSender(fakeClock);
    }
}
