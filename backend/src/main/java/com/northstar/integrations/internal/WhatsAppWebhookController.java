package com.northstar.integrations.internal;

import com.northstar.tenancy.WebhookRouting;
import com.northstar.tenancy.WebhookRouting.Parked;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Meta's WhatsApp Cloud API webhook (step 2.2, integration-specs.md sections 1 and 2), at {@code /webhooks/whatsapp},
 * outside /api/v1: a {@code @Controller}, not a {@code @RestController}, so the API prefix does not apply. Public (no
 * token; its own security chain), so it trusts nothing until the signature is checked.
 *
 * <p>POST, in CLAUDE.md's order: refuse a body over the limit (413) before reading past it; verify the signature on the
 * raw bytes before any parsing (bad or missing: 401, parked without payload, P15); then route, store, dedupe and queue
 * ({@link WhatsAppWebhookIntake}), and answer 200. A signed body that is unreadable, or carries nothing storable, is
 * still a 200, so Meta does not retry it for 7 days.
 *
 * <p>GET is Meta's subscription handshake: {@code hub.mode=subscribe} and the verify token, compared in constant time;
 * the challenge, which Meta sends as an integer, is echoed as plain text only then, and only if it is digits.
 *
 * <p>Logs carry status codes, sizes and IDs only: never the body, the signature, the token or the challenge.
 */
@Controller
@RequestMapping("/webhooks/whatsapp")
class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private static final Pattern CHALLENGE = Pattern.compile("[0-9]{1,64}");

    private final WhatsAppWebhookSettings settings;
    private final WhatsAppWebhookIntake intake;
    private final WebhookRouting routing;
    private final UnroutedRateLimiter limiter;
    private final int maxBodyBytes;

    WhatsAppWebhookController(
            WhatsAppWebhookSettings settings,
            WhatsAppWebhookIntake intake,
            WebhookRouting routing,
            UnroutedRateLimiter limiter,
            @Value("${northstar.webhooks.max-body-bytes}") int maxBodyBytes) {
        if (maxBodyBytes < 1) throw new IllegalStateException("northstar.webhooks.max-body-bytes must be positive");
        this.settings = settings;
        this.intake = intake;
        this.routing = routing;
        this.limiter = limiter;
        this.maxBodyBytes = maxBodyBytes;
    }

    @GetMapping
    @ResponseBody
    ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (!settings.configured()) throw WebhookRejectedException.notConfigured();
        boolean tokenMatches = settings.verifyTokenMatches(token); // always compared, whatever else is wrong
        if (!"subscribe".equals(mode)
                || !tokenMatches
                || challenge == null
                || !CHALLENGE.matcher(challenge).matches()) {
            log.warn("webhook.verification provider=whatsapp status=403");
            throw WebhookRejectedException.verificationFailed();
        }
        log.info("webhook.verification provider=whatsapp status=200");
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping
    @ResponseBody
    ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        if (!settings.configured()) throw WebhookRejectedException.notConfigured();
        byte[] body = readBounded(request);
        byte[] key = settings.appSecret().orElseThrow();
        if (!WebhookSignature.matches(key, body, request.getHeader(WebhookSignature.WHATSAPP_HEADER))) {
            parkInvalidSignature(body.length);
            throw WebhookRejectedException.invalidSignature();
        }
        intake.receive(body);
        return ResponseEntity.ok().build();
    }

    /** The raw body, refusing (413) a declared length over the limit before reading and a longer stream at the limit. */
    private byte[] readBounded(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            log.warn("webhook.too_large provider=whatsapp declared_bytes={} status=413", declared);
            throw WebhookRejectedException.tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) {
                log.warn("webhook.too_large provider=whatsapp read_bytes_over={} status=413", maxBodyBytes);
                throw WebhookRejectedException.tooLarge();
            }
            return body;
        }
    }

    /** P15: metadata only, within both rate limits; the answer is the same 401 whether or not a row was written. */
    private void parkInvalidSignature(int bodyBytes) {
        if (!limiter.tryAcquire(UnroutedRateLimiter.Reason.INVALID_SIGNATURE)) return;
        Parked parked = routing.parkInvalidSignature(WhatsAppWebhookIntake.PROVIDER, bodyBytes);
        switch (parked.outcome()) {
            case STORED ->
                log.warn(
                        "webhook.signature_invalid provider=whatsapp body_bytes={} unrouted_id={} status=401",
                        bodyBytes,
                        parked.unroutedId().orElseThrow());
            case RATE_LIMITED -> limiter.dropped(UnroutedRateLimiter.Reason.INVALID_SIGNATURE);
            case DUPLICATE -> {}
        }
    }
}
