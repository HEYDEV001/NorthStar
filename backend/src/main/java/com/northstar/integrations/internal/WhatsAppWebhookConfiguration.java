package com.northstar.integrations.internal;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The WhatsApp webhook's settings (step 2.2). Reads configuration only, never the database, so a misconfigured API
 * container stops at startup before it connects.
 */
@Configuration(proxyBeanMethods = false)
class WhatsAppWebhookConfiguration {

    @Bean
    WhatsAppWebhookSettings whatsAppWebhookSettings(
            @Value("${northstar.webhooks.whatsapp.app-secret}") String appSecret,
            @Value("${northstar.webhooks.whatsapp.verify-token}") String verifyToken,
            @Value("${northstar.worker.enabled}") boolean workerEnabled) {
        return WhatsAppWebhookSettings.of(appSecret, verifyToken, workerEnabled);
    }

    @Bean
    UnroutedRateLimiter unroutedRateLimiter(
            @Value("${northstar.webhooks.unrouted.invalid-signature-per-minute}") int invalidSignaturePerMinute,
            @Value("${northstar.webhooks.unrouted.unknown-account-per-minute}") int unknownAccountPerMinute) {
        return new UnroutedRateLimiter(invalidSignaturePerMinute, unknownAccountPerMinute, Clock.systemUTC());
    }
}
