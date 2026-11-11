package com.northstar.notifications;

import com.northstar.fakes.FakeClock;
import com.northstar.fakes.FakeEmailSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Test-only (step 1.12): every test context sends email through the fake, never Resend or Mailpit. */
@Configuration(proxyBeanMethods = false)
class FakeEmailConfiguration {

    @Bean
    @Primary
    FakeEmailSender fakeEmailSender(FakeClock fakeClock) {
        return new FakeEmailSender(fakeClock);
    }
}
