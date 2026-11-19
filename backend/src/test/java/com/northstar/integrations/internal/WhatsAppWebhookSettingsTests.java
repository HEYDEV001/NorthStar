package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * When the WhatsApp webhook is active (step 2.2): both secrets set, whatever the worker flag says; either missing with
 * the worker off (an API container) refuses startup, naming the setting; either missing with the worker on starts, and
 * the paths answer 503 (the 503 itself through the real app is in WhatsAppWebhookNotConfiguredTests). Errors never
 * carry a value.
 */
class WhatsAppWebhookSettingsTests {

    private static final String SECRET = "app-" + UUID.randomUUID().toString().replace("-", "");
    private static final String TOKEN = "token-" + UUID.randomUUID() + UUID.randomUUID();

    private final ApplicationContextRunner app = new ApplicationContextRunner()
            .withUserConfiguration(WhatsAppWebhookConfiguration.class)
            .withPropertyValues(
                    "northstar.webhooks.whatsapp.app-secret=" + SECRET,
                    "northstar.webhooks.whatsapp.verify-token=" + TOKEN,
                    "northstar.webhooks.unrouted.invalid-signature-per-minute=30",
                    "northstar.webhooks.unrouted.unknown-account-per-minute=120");

    @Test
    void bothSecretsSetMeansActiveWithTheWorkerOnOrOff() {
        for (String worker : List.of("false", "true")) {
            app.withPropertyValues("northstar.worker.enabled=" + worker).run(context -> {
                assertThat(context).hasNotFailed();
                WhatsAppWebhookSettings settings = context.getBean(WhatsAppWebhookSettings.class);
                assertThat(settings.configured()).as("worker " + worker).isTrue();
                assertThat(settings.verifyTokenMatches(TOKEN)).isTrue();
                assertThat(settings.verifyTokenMatches(TOKEN + "x")).isFalse();
                assertThat(settings.verifyTokenMatches(null)).isFalse();
                assertThat(settings.toString()).doesNotContain(SECRET).doesNotContain(TOKEN);
            });
        }
    }

    @Test
    void aMissingSecretOnAnApiContainerRefusesStartupNamingTheSetting() {
        assertRefused(
                "northstar.webhooks.whatsapp.app-secret=",
                "NORTHSTAR_WHATSAPP_APP_SECRET is required when the worker is off (an API container)");
        assertRefused(
                "northstar.webhooks.whatsapp.verify-token=",
                "NORTHSTAR_WHATSAPP_VERIFY_TOKEN is required when the worker is off (an API container)");
        assertRefused(
                "northstar.webhooks.whatsapp.verify-token=   ",
                "NORTHSTAR_WHATSAPP_VERIFY_TOKEN is required when the worker is off (an API container)");
    }

    @Test
    void aMissingSecretWithTheWorkerOnStartsUnconfigured() {
        for (String blank :
                List.of("northstar.webhooks.whatsapp.app-secret=", "northstar.webhooks.whatsapp.verify-token=")) {
            app.withPropertyValues("northstar.worker.enabled=true", blank).run(context -> {
                assertThat(context).hasNotFailed();
                WhatsAppWebhookSettings settings = context.getBean(WhatsAppWebhookSettings.class);
                assertThat(settings.configured()).as(blank).isFalse();
                assertThat(settings.appSecret()).isEmpty();
                assertThat(settings.verifyTokenMatches(TOKEN)).isFalse();
            });
        }
    }

    @Test
    void aMalformedSecretRefusesStartupEvenWithTheWorkerOn() {
        String rule = " must be 32 to 512 printable ASCII characters with no spaces";
        for (String worker : List.of("northstar.worker.enabled=false", "northstar.worker.enabled=true")) {
            assertRefused(
                    worker, "northstar.webhooks.whatsapp.app-secret=short", "NORTHSTAR_WHATSAPP_APP_SECRET" + rule);
            assertRefused(
                    worker,
                    "northstar.webhooks.whatsapp.verify-token=" + TOKEN.substring(0, 10) + " " + TOKEN,
                    "NORTHSTAR_WHATSAPP_VERIFY_TOKEN" + rule);
        }
    }

    private void assertRefused(String override, String message) {
        assertRefused("northstar.worker.enabled=false", override, message);
    }

    private void assertRefused(String worker, String override, String message) {
        app.withPropertyValues(worker, override).run(context -> {
            assertThat(context).hasFailed();
            List<String> messages = new ArrayList<>();
            for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                messages.add(String.valueOf(cause.getMessage()));
            }
            assertThat(messages).as(override).contains(message);
            for (String each : messages) assertThat(each).doesNotContain(SECRET).doesNotContain(TOKEN);
        });
    }
}
