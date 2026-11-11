package com.northstar.integrations.internal;

import com.northstar.integrations.EmailSender;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the email adapter (step 1.12): Resend by default; Mailpit only when NORTHSTAR_EMAIL_PROVIDER=mailpit, for
 * local development. Only the worker sends email, so the sender address and Resend's key are required only when the
 * worker is on; the check reads configuration only, never the database.
 */
@Configuration(proxyBeanMethods = false)
class EmailConfiguration {

    @Bean
    EmailSender emailSender(
            @Value("${northstar.email.provider}") String provider,
            @Value("${northstar.email.from}") String from,
            @Value("${northstar.email.resend.api-key}") String resendApiKey,
            @Value("${northstar.email.resend.base-url}") URI resendBaseUrl,
            @Value("${northstar.email.mailpit.url}") URI mailpitUrl,
            @Value("${northstar.worker.enabled}") boolean workerEnabled) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (workerEnabled && from.isBlank()) {
            throw new IllegalStateException("NORTHSTAR_EMAIL_FROM is required when the worker is on");
        }
        return switch (provider) {
            case "resend" -> {
                if (workerEnabled && resendApiKey.isBlank()) {
                    throw new IllegalStateException("RESEND_API_KEY is required when the worker is on");
                }
                yield new ResendEmailSender(http, resendBaseUrl, resendApiKey, from, Duration.ofSeconds(10));
            }
            case "mailpit" -> new MailpitEmailSender(http, mailpitUrl, from.isBlank() ? "invites@localhost" : from);
            default -> throw new IllegalStateException("NORTHSTAR_EMAIL_PROVIDER must be resend or mailpit");
        };
    }
}
