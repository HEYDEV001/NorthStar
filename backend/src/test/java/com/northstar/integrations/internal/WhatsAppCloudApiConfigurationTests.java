package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The Cloud API sender's settings (step 2.6b, integration-specs.md section 2): the Graph API version is pinned to v26.0
 * in the repo's application.properties, with Meta's base URL; a malformed version or base URL refuses startup, naming
 * the setting and never its value; the HTTP client gives up well before the send handler; and the bean built really
 * calls the configured version.
 */
class WhatsAppCloudApiConfigurationTests {

    private final ApplicationContextRunner app = new ApplicationContextRunner()
            .withUserConfiguration(WhatsAppCloudApiConfiguration.class)
            .withPropertyValues(
                    "northstar.whatsapp.graph.base-url=https://graph.facebook.com",
                    "northstar.whatsapp.graph.version=v26.0");

    @Test
    void theVersionIsPinnedToV26InTheRepoWithMetasBaseUrl() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            properties.load(in);
        }

        assertThat(properties.getProperty("northstar.whatsapp.graph.version"))
                .as("bump it only as integration-specs.md section 2 says")
                .isEqualTo("v26.0");
        assertThat(properties.getProperty("northstar.whatsapp.graph.base-url")).isEqualTo("https://graph.facebook.com");
    }

    @Test
    void theAppBuildsTheCloudApiSender() {
        app.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WhatsAppSender.class)).isInstanceOf(CloudApiWhatsAppSender.class);
        });
    }

    @Test
    void aMalformedVersionRefusesStartupNamingTheSetting() {
        for (String version : List.of("26.0", "v26", "V26.0", "latest", "v26.0/", "v26.0/../me", "v26.0.1", "")) {
            app.withPropertyValues("northstar.whatsapp.graph.version=" + version)
                    .run(context -> assertThat(context)
                            .as(version)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessage("northstar.whatsapp.graph.version must be a Graph API version such as v26.0"));
        }
    }

    @Test
    void aBaseUrlThatIsNotBareHttpsRefusesStartupNamingTheSetting() {
        String secret = "pw-" + UUID.randomUUID();
        for (String url : List.of(
                "http://graph.facebook.com",
                "https://graph.facebook.com/v26.0",
                "https://user:" + secret + "@graph.facebook.com",
                "https://graph.facebook.com?x=1",
                "https://graph.facebook.com#x",
                "ftp://graph.facebook.com",
                "graph.facebook.com",
                "http://localhost:8080",
                "http://10.0.0.5",
                "")) {
            app.withPropertyValues("northstar.whatsapp.graph.base-url=" + url)
                    .run(context -> assertThat(context)
                            .as(url)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessage("northstar.whatsapp.graph.base-url must be https://host with no path, query or"
                                    + " credentials")
                            .hasMessageNotContaining(secret));
        }
        for (String url : List.of("https://graph.facebook.com/", "http://127.0.0.1:8080", "http://[::1]:8080")) {
            app.withPropertyValues("northstar.whatsapp.graph.base-url=" + url)
                    .run(context -> assertThat(context).as(url).hasNotFailed());
        }
    }

    @Test
    void theHttpClientGivesUpWellBeforeTheHandler() {
        assertThat(WhatsAppCloudApiConfiguration.CONNECT_TIMEOUT.plus(WhatsAppCloudApiConfiguration.REQUEST_TIMEOUT))
                .as("at least 10 seconds left to store Meta's ID before the handler's timeout")
                .isLessThanOrEqualTo(WhatsAppSendHandler.TIMEOUT.minusSeconds(10));
    }

    @Test
    void theSenderCallsTheConfiguredVersion() throws IOException {
        HttpServer standIn = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        standIn.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            byte[] body = "{\"messages\": [{\"id\": \"wamid.TEST\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        standIn.start();
        try {
            app.withPropertyValues("northstar.whatsapp.graph.base-url=http://127.0.0.1:"
                            + standIn.getAddress().getPort())
                    .run(context -> {
                        WhatsAppSender sender = context.getBean(WhatsAppSender.class);
                        try (Secret token = Secret.ofUtf8("EAAG-test")) {
                            assertThat(sender.send(
                                            new WhatsAppSender.OutgoingWhatsApp(
                                                    UUID.randomUUID(),
                                                    "106540352242922",
                                                    "+919876543210",
                                                    new WhatsAppSender.Text("Hello")),
                                            token))
                                    .isEqualTo(new WhatsAppSender.Accepted("wamid.TEST"));
                        }
                    });
        } finally {
            standIn.stop(0);
        }
        assertThat(path.get()).isEqualTo("/v26.0/106540352242922/messages");
    }
}
