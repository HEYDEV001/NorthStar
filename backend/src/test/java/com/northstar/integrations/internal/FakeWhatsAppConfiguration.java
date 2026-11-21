package com.northstar.integrations.internal;

import com.northstar.fakes.FakeClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only (steps 2.6a and 2.8a): every test context sends WhatsApp and reads numbers through the fakes, never Meta. Primary, so it wins over the
 * Cloud API adapter (step 2.6b), which tests reach only against a loopback stand-in.
 */
@Configuration(proxyBeanMethods = false)
class FakeWhatsAppConfiguration {

    @Bean
    @Primary
    FakeWhatsAppSender fakeWhatsAppSender(FakeClock fakeClock) {
        return new FakeWhatsAppSender(fakeClock);
    }

    /** The sync's number reader (step 2.8a), faked the same way; the Cloud API reader is reached only on loopback. */
    @Bean
    @Primary
    FakeWhatsAppAccountReader fakeWhatsAppAccountReader(FakeClock fakeClock) {
        return new FakeWhatsAppAccountReader(fakeClock);
    }
}
