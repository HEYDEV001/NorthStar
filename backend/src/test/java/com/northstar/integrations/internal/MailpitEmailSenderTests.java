package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.integrations.OutgoingEmail;
import com.northstar.workflow.ActionOutcome;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The local-only Mailpit adapter (step 1.12): it refuses any non-local URL and posts to Mailpit's send API. */
class MailpitEmailSenderTests {

    @Test
    void itRefusesAnyHostButLocalhost() {
        for (String url :
                new String[] {"https://mailpit.example.com", "http://10.0.0.5:54324", "http://0.0.0.0:54324"}) {
            assertThatThrownBy(() -> new MailpitEmailSender(HttpClient.newHttpClient(), URI.create(url), "a@b.test"))
                    .as(url)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only sends to localhost");
        }
    }

    @Test
    void itPostsTheEmailToMailpitsSendApi() throws IOException {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer mailpit = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mailpit.createContext("/api/v1/send", exchange -> {
            request.set(exchange.getRequestMethod() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ID\": \"m-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        mailpit.start();
        try {
            MailpitEmailSender sender = new MailpitEmailSender(
                    HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + mailpit.getAddress().getPort()),
                    "Northstar <invites@localhost>");

            ActionOutcome outcome = sender.send(new OutgoingEmail("a@b.test", "Subject", "Body"), "k");

            assertThat(outcome).isInstanceOf(ActionOutcome.Succeeded.class);
            assertThat(request.get())
                    .isEqualTo("POST {\"From\":{\"Email\":\"invites@localhost\",\"Name\":\"Northstar\"},"
                            + "\"To\":[{\"Email\":\"a@b.test\"}],\"Subject\":\"Subject\",\"Text\":\"Body\"}");
        } finally {
            mailpit.stop(0);
        }
    }
}
