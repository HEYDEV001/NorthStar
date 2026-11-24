package com.northstar.devtools;

import com.northstar.fakes.WhatsAppPayloads;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Posts one correctly signed WhatsApp text message to the webhook twice, byte for byte (step 2.11): the second is the
 * duplicate Meta would send on a retry, which must change nothing (P1). The signature is what Meta would make:
 * HMAC-SHA256 of the exact body with the app secret, in {@code X-Hub-Signature-256}. The secret is read from
 * .env.local, kept in memory, and never printed or put in an argument.
 *
 * <p>The sender is always a synthetic number (+910000......: no Indian number starts with 0), so a run can never act as
 * a real person, opt one out or message one. A message can be dated in the past, which closes the 24-hour window
 * without touching the database. Only an HTTPS address, or plain HTTP on this machine, is posted to, and only its
 * {@code /webhooks/whatsapp}.
 */
final class PostSignedWebhookTwice {

    static final String DEFAULT_TEXT = "Hello from the Northstar Phase 2 gate";
    static final int MAX_HOURS_AGO = 720;

    private static final Pattern SYNTHETIC = Pattern.compile("910000[0-9]{6}");
    private static final Pattern META_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;

    PostSignedWebhookTwice() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    PostSignedWebhookTwice(HttpClient http) {
        this.http = http;
    }

    /** One message to post: from a synthetic number, dated so many hours ago. */
    record Message(String phoneNumberId, String wabaId, String from, String text, int hoursAgo) {

        Message {
            require(META_ID, phoneNumberId, "--phone-number-id is the connected number's ID.");
            require(META_ID, wabaId, "--waba-id is the WhatsApp Business Account ID.");
            require(
                    SYNTHETIC,
                    from,
                    "--from must be a synthetic number, 910000 and six digits (no real number looks like that).");
            if (text == null || text.isBlank() || text.length() > 1000) {
                throw new LocalToolException("argument_invalid", "--text is 1 to 1000 characters.");
            }
            if (hoursAgo < 0 || hoursAgo > MAX_HOURS_AGO) {
                throw new LocalToolException("argument_invalid", "--hours-ago is 0 to " + MAX_HOURS_AGO + ".");
            }
        }

        private static void require(Pattern pattern, String value, String message) {
            if (value == null || !pattern.matcher(value).matches()) {
                throw new LocalToolException("argument_invalid", message);
            }
        }
    }

    /** The two answers, and the message's ID, which is how it can be found. */
    record Answers(int first, int second, String wamid) {}

    Answers postTwice(URI target, Message message, byte[] appSecret) {
        URI webhook = checked(target);
        WhatsAppPayloads.Inbound inbound = WhatsAppPayloads.inbound(message.phoneNumberId())
                .wamid("wamid.GATE"
                        + UUID.randomUUID().toString().replace("-", "").toUpperCase())
                .from(message.from())
                .profileName("Gate test")
                .sentAt(Instant.now().minus(Duration.ofHours(message.hoursAgo())))
                .text(message.text());
        byte[] body = withWaba(inbound.json(), message.wabaId()).getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=" + HexFormat.of().formatHex(hmac(appSecret, body));
        HttpRequest request = HttpRequest.newBuilder(webhook)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return new Answers(post(request), post(request), inbound.wamid());
    }

    /** HTTPS, or plain HTTP on this machine, and exactly the webhook's path: the signature goes nowhere else. */
    static URI checked(URI target) {
        boolean web = "https".equals(target.getScheme())
                || ("http".equals(target.getScheme()) && LocalOnly.isLoopback(target.getHost()));
        boolean webhook = "/webhooks/whatsapp".equals(target.getRawPath())
                && target.getRawQuery() == null
                && target.getRawFragment() == null
                && target.getUserInfo() == null
                && target.getHost() != null;
        if (!web || !webhook) {
            throw new LocalToolException(
                    "url_invalid",
                    "--url must be https://<host>/webhooks/whatsapp, or http:// on this machine. Nothing was sent.");
        }
        return target;
    }

    private int post(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new LocalToolException(
                    "unreachable", "The webhook did not answer. Is the API running, and the tunnel up?");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalToolException("interrupted", "Interrupted.");
        }
    }

    /** The entry's ID is the business account's, as in Meta's payloads; the payload builder writes a placeholder. */
    private static String withWaba(String json, String wabaId) {
        ObjectNode root = (ObjectNode) JSON.readTree(json);
        ((ObjectNode) root.get("entry").get(0)).put("id", wabaId);
        return JSON.writeValueAsString(root);
    }

    private static byte[] hmac(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
