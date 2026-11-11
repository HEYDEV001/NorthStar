package com.northstar.integrations.internal;

import com.northstar.integrations.EmailSender;
import com.northstar.integrations.OutgoingEmail;
import com.northstar.workflow.ActionOutcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development only (NORTHSTAR_EMAIL_PROVIDER=mailpit): hands the email to the Mailpit that the Supabase CLI runs
 * (POST /api/v1/send), so invites can be accepted locally without a Resend key. It refuses any URL whose host is not
 * localhost or 127.0.0.1, so it can never deliver real mail. Mailpit has no idempotency keys; locally that is fine.
 */
final class MailpitEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(MailpitEmailSender.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");
    private static final Pattern NAMED_ADDRESS = Pattern.compile("^\\s*(.*?)\\s*<([^>]+)>\\s*$");

    private final HttpClient http;
    private final URI sendEndpoint;
    private final Map<String, String> from;

    MailpitEmailSender(HttpClient http, URI baseUrl, String from) {
        if (baseUrl.getHost() == null || !LOCAL_HOSTS.contains(baseUrl.getHost())) {
            throw new IllegalStateException("The Mailpit email provider only sends to localhost or 127.0.0.1");
        }
        this.http = http;
        this.sendEndpoint = baseUrl.resolve("/api/v1/send");
        this.from = address(from);
    }

    @Override
    public ActionOutcome send(OutgoingEmail email, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("From", from);
        body.put("To", List.of(Map.of("Email", email.to())));
        body.put("Subject", email.subject());
        body.put("Text", email.text());
        HttpRequest request = HttpRequest.newBuilder(sendEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.MAPPER.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("mailpit.response status={}", response.statusCode());
            if (response.statusCode() / 100 == 2) return ActionOutcome.succeeded(null);
            return response.statusCode() >= 500
                    ? ActionOutcome.retryable("http_" + response.statusCode())
                    : ActionOutcome.permanent("http_" + response.statusCode());
        } catch (IOException e) {
            return ActionOutcome.retryable("connection_error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionOutcome.retryable("interrupted");
        }
    }

    /** "Name <address>" or a bare address, as Mailpit's {Email, Name}, in that order. */
    private static Map<String, String> address(String from) {
        Map<String, String> address = new LinkedHashMap<>();
        Matcher named = NAMED_ADDRESS.matcher(from);
        if (named.matches()) {
            address.put("Email", named.group(2));
            address.put("Name", named.group(1));
        } else {
            address.put("Email", from.trim());
        }
        return address;
    }
}
