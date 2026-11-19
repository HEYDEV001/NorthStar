package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A process with the worker on and the WhatsApp secrets missing (step 2.2) starts, and the webhook paths answer 503;
 * everything else works. (An API container in the same state refuses to start: WhatsAppWebhookSettingsTests.)
 */
@SpringBootTest(
        properties = {
            "northstar.worker.enabled=true",
            "northstar.webhooks.whatsapp.app-secret=",
            "northstar.webhooks.whatsapp.verify-token="
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WhatsAppWebhookNotConfiguredTests {

    private final MockMvcTester mvc;
    private final WhatsAppWebhookSettings settings;

    WhatsAppWebhookNotConfiguredTests(@Autowired MockMvcTester mvc, @Autowired WhatsAppWebhookSettings settings) {
        this.mvc = mvc;
        this.settings = settings;
    }

    @Test
    void theWebhookPathsAnswer503AndTheRestOfTheAppRuns() {
        assertThat(settings.configured()).isFalse();

        MvcTestResult post = mvc.post()
                .uri("/webhooks/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"object\":\"whatsapp_business_account\",\"entry\":[]}")
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                .exchange();
        assertThat(post).hasStatus(503);
        assertThat(post).bodyJson().extractingPath("$.code").isEqualTo("webhooks_not_configured");

        MvcTestResult get = mvc.get()
                .uri("/webhooks/whatsapp")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "anything-at-all-anything-at-all-x")
                .param("hub.challenge", "1")
                .exchange();
        assertThat(get).hasStatus(503);

        assertThat(mvc.get().uri("/actuator/health").exchange()).hasStatusOk();
    }
}
