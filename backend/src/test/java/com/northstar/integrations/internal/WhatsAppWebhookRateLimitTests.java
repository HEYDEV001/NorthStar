package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestcontainersConfiguration;
import com.northstar.TestcontainersConfiguration.WhatsAppTestSecrets;
import com.northstar.fakes.FakeClock;
import com.northstar.fakes.WebhookDelivery;
import com.northstar.fakes.WebhookScript;
import com.northstar.fakes.WhatsAppPayloads;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The unrouted write path is rate-limited (step 2.2): over the limit nothing more is parked, and the answer does not
 * change, so a sender cannot tell. Small limits here, in their own context, so other tests keep the defaults; the
 * database's cap across instances is covered in WebhookRoutingTests.
 */
@SpringBootTest(
        properties = {
            "northstar.webhooks.unrouted.invalid-signature-per-minute=3",
            "northstar.webhooks.unrouted.unknown-account-per-minute=2"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppWebhookRateLimitTests {

    private final MockMvcTester mvc;
    private final WebhookScript script;
    private final JdbcClient admin;

    WhatsAppWebhookRateLimitTests(
            @Autowired MockMvcTester mvc, @Autowired WhatsAppTestSecrets secrets, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.script = new WebhookScript(secrets.signer(), new FakeClock(Instant.parse("2026-11-19T10:00:00Z")));
        this.admin = postgres.jdbc();
    }

    @Test
    void P15_badSignaturesBeyondTheLimitAreRefusedAlikeButNotParked(CapturedOutput output) {
        long before = count("invalid_signature");
        for (int i = 0; i < 6; i++) {
            String body = WhatsAppPayloads.delivery()
                    .message(WhatsAppPayloads.phoneNumberId(), WhatsAppPayloads.wamid(), "junk " + i)
                    .json();
            MvcTestResult result = post(script.withInvalidSignature(body));
            assertThat(result).as("attempt " + i).hasStatus(401);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("invalid_signature");
        }
        assertThat(count("invalid_signature")).isEqualTo(before + 3);
        assertThat(output.getAll()).contains("webhook.unrouted_rate_limited reason=invalid_signature dropped=1");
    }

    @Test
    void P16_unknownAccountsBeyondTheLimitAreAcceptedAlikeButNotParked() {
        long before = count("unknown_account");
        for (int i = 0; i < 5; i++) {
            String body = WhatsAppPayloads.delivery()
                    .message(WhatsAppPayloads.phoneNumberId(), WhatsAppPayloads.wamid(), "hello " + i)
                    .json();
            assertThat(post(script.deliver(body))).as("attempt " + i).hasStatusOk();
        }
        assertThat(count("unknown_account")).isEqualTo(before + 2);
    }

    private MvcTestResult post(WebhookDelivery delivery) {
        var request = mvc.post()
                .uri("/webhooks/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(delivery.body());
        delivery.headers().forEach((name, value) -> request.header(name, value));
        return request.exchange();
    }

    private long count(String reason) {
        return admin.sql("select count(*) from ingest.unrouted_webhooks where reason = :r")
                .param("r", reason)
                .query(Long.class)
                .single();
    }
}
