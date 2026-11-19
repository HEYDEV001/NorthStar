package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.fakes.HmacSigner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Meta's X-Hub-Signature-256 check (step 2.2, P15): the exact header format, the raw bytes, the right key. */
class WebhookSignatureTests {

    private final HmacSigner signer = new HmacSigner("X-Hub-Signature-256", "sha256=");
    private final byte[] key = signer.secret().getBytes(StandardCharsets.UTF_8);
    private final byte[] body =
            "{\"object\":\"whatsapp_business_account\",\"entry\":[]}".getBytes(StandardCharsets.UTF_8);

    @Test
    void P15_aSignatureFromTheAppSecretOverTheRawBytesMatches() {
        assertThat(WebhookSignature.matches(key, body, signer.sign(body))).isTrue();
    }

    @Test
    void P15_aSignatureFromAnotherSecretDoesNotMatch() {
        HmacSigner impostor = new HmacSigner("X-Hub-Signature-256", "sha256=");
        assertThat(WebhookSignature.matches(key, body, impostor.sign(body))).isFalse();
    }

    @Test
    void P15_theSameJsonWithDifferentBytesDoesNotMatch() {
        // Meta signs the bytes it sends: whitespace or escaping changed by re-serialising breaks the signature.
        byte[] reformatted =
                "{\"object\": \"whatsapp_business_account\", \"entry\": []}".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignature.matches(key, reformatted, signer.sign(body)))
                .isFalse();
        byte[] escaped =
                "{\"object\":\"whatsapp_business_\\u0061ccount\",\"entry\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookSignature.matches(key, escaped, signer.sign(body))).isFalse();
    }

    @Test
    void P15_aMissingOrMalformedHeaderDoesNotMatch() {
        String good = signer.sign(body);
        String hex = good.substring("sha256=".length());
        List<String> malformed = List.of(
                "",
                "sha256=",
                hex,
                "SHA256=" + hex,
                "sha1=" + hex,
                "sha256=" + hex.toUpperCase(),
                "sha256=" + hex.substring(1),
                "sha256=" + hex + "0",
                " sha256=" + hex,
                "sha256=" + hex.substring(0, 63) + "g");
        assertThat(WebhookSignature.matches(key, body, null)).isFalse();
        for (String header : malformed) {
            assertThat(WebhookSignature.matches(key, body, header)).as(header).isFalse();
        }
    }
}
