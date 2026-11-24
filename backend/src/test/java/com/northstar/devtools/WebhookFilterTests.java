package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Capability;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * The webhook-only filter the Cloudflare tunnel points at in the Phase 2 gate (step 2.11), run with the pinned image
 * and the same restrictions as {@code scripts/local/gate filter}: only GET and POST on exactly /webhooks/whatsapp reach
 * the API, everything else is a 404, the bytes arrive unchanged, no login crosses it, and it logs no request.
 *
 * <p>In front of a stand-in for the API that answers what it was asked. Requests are written to the socket as they are,
 * so no client library tidies an odd spelling of a path before it is sent.
 */
class WebhookFilterTests {

    private static final Path SCRIPTS =
            Path.of("..", "scripts", "local").toAbsolutePath().normalize();

    /** Answers with the request it got. */
    private static final String STAND_IN = """
            pid /tmp/nginx.pid;
            error_log stderr crit;
            events { worker_connections 64; }
            http {
              access_log off;
              client_max_body_size 8m;
              client_body_temp_path /tmp/a; proxy_temp_path /tmp/b; fastcgi_temp_path /tmp/c;
              uwsgi_temp_path /tmp/d; scgi_temp_path /tmp/e;
              server {
                listen 8080;
                location / {
                  return 200 "api $request_method $request_uri sig=$http_x_hub_signature_256 auth=$http_authorization cookie=$http_cookie len=$content_length type=$content_type";
                }
              }
            }
            """;

    private static Network network;
    private static GenericContainer<?> api;
    private static GenericContainer<?> filter;

    @BeforeAll
    static void startTheFilterInFrontOfTheStandIn() throws IOException {
        // The digest alone pins the image; Testcontainers reads a tag and a digest together as one name.
        DockerImageName image = DockerImageName.parse(pinnedImage().replaceFirst(":[^@/]+@", "@"));
        network = Network.newNetwork();
        api = new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases("host.docker.internal")
                .withCopyToContainer(Transferable.of(STAND_IN), "/etc/nginx/nginx.conf")
                .waitingFor(Wait.forLogMessage(".*Configuration complete.*", 1));
        api.start();
        filter = new GenericContainer<>(image)
                .withNetwork(network)
                .withFileSystemBind(
                        SCRIPTS.resolve("webhook-only.conf").toString(), "/etc/nginx/nginx.conf", BindMode.READ_ONLY)
                .withCreateContainerCmdModifier(command -> command.withUser("101:101")
                        .getHostConfig()
                        .withReadonlyRootfs(true)
                        .withTmpFs(Map.of("/tmp", ""))
                        .withCapDrop(Capability.ALL)
                        .withSecurityOpts(List.of("no-new-privileges")))
                .withExposedPorts(8088)
                .waitingFor(Wait.forListeningPort());
        filter.start();
    }

    @AfterAll
    static void stop() {
        if (filter != null) filter.stop();
        if (api != null) api.stop();
        if (network != null) network.close();
    }

    @Test
    void metasHandshakeAndDeliveriesReachTheApiUnchanged() throws IOException {
        Answer handshake = send("GET", "/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=t%2Bt&hub.challenge=42");
        assertThat(handshake.status()).isEqualTo(200);
        assertThat(handshake.body())
                .startsWith("api GET /webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=t%2Bt&hub.challenge=42 ");

        byte[] body = "{\"object\": \"whatsapp_business_account\", \"emoji\": \"👋\"}".getBytes(StandardCharsets.UTF_8);
        Answer delivery = send(
                "POST",
                "/webhooks/whatsapp",
                body,
                "X-Hub-Signature-256: sha256=abc123",
                "Content-Type: application/json",
                "Authorization: Bearer stolen",
                "Cookie: session=stolen");
        assertThat(delivery.status()).isEqualTo(200);
        assertThat(delivery.body())
                .isEqualTo("api POST /webhooks/whatsapp sig=sha256=abc123 auth= cookie= len=" + body.length
                        + " type=application/json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "PUT", "PATCH", "DELETE", "OPTIONS", "PROPFIND", "get"})
    void noOtherMethodReachesTheApi(String method) throws IOException {
        Answer answer = send(method, "/webhooks/whatsapp");
        assertThat(answer.status()).isIn(404, 400); // a method nginx does not know at all is a 400
        assertThat(answer.body()).doesNotContain("api ");
    }

    @Test
    void traceIsRefusedByNginxItself() throws IOException {
        assertThat(send("TRACE", "/webhooks/whatsapp").status()).isEqualTo(405);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/",
                "/api/v1/me",
                "/api/v1/businesses",
                "/api/v1/leads",
                "/actuator/health",
                "/error",
                "/webhooks",
                "/webhooks/",
                "/webhooks/whatsapp/",
                "/webhooks/whatsapp/x",
                "/webhooks/whatsappx",
                "/WEBHOOKS/WHATSAPP",
                "/webhooks/razorpay",
                // Spellings that nginx would normalise to the webhook's path, or out of it.
                "//webhooks/whatsapp",
                "/webhooks//whatsapp",
                "/webhooks/./whatsapp",
                "/webhooks/whatsapp/.",
                "/api/v1/../../webhooks/whatsapp",
                "/webhooks/whatsapp/../../api/v1/me",
                "/webhooks/whatsapp%2f..%2f..%2fapi%2fv1%2fme",
                "/webhooks/%77hatsapp",
                "/webhooks%2Fwhatsapp",
                "/webhooks/whatsapp;jsessionid=1",
                "/webhooks/whatsapp;/../../api/v1/me",
                "/webhooks/whatsapp%00",
                "/webhooks/whatsapp%20",
                "/webhooks/whatsapp%3Fx=1"
            })
    void noOtherPathReachesTheApi(String path) throws IOException {
        for (String method : List.of("GET", "POST")) {
            Answer answer = send(method, path);
            assertThat(answer.status()).as(method + " " + path).isIn(404, 400);
            assertThat(answer.body()).doesNotContain("api ");
        }
    }

    @Test
    void aBodyLargerThanMetasIsRefusedHere() throws IOException {
        Answer answer = send("POST", "/webhooks/whatsapp", new byte[3 * 1024 * 1024 + 1]);
        assertThat(answer.status()).isEqualTo(413);
    }

    @Test
    void itLogsNoRequestEvenWhenTheApiIsDown() throws IOException {
        send("GET", "/webhooks/whatsapp?hub.verify_token=must-not-be-logged");
        GenericContainer<?> alone = new GenericContainer<>(
                        DockerImageName.parse(pinnedImage().replaceFirst(":[^@/]+@", "@")))
                .withExtraHost("host.docker.internal", "127.0.0.1")
                .withFileSystemBind(
                        SCRIPTS.resolve("webhook-only.conf").toString(), "/etc/nginx/nginx.conf", BindMode.READ_ONLY)
                .withExposedPorts(8088)
                .waitingFor(Wait.forListeningPort());
        try (alone) {
            alone.start();
            Answer answer = send(alone, "GET", "/webhooks/whatsapp?hub.verify_token=must-not-be-logged", null);
            assertThat(answer.status()).as("nothing answers behind it").isEqualTo(502);
            assertThat(alone.getLogs()).doesNotContain("must-not-be-logged", "/webhooks/whatsapp");
        }
        assertThat(filter.getLogs()).doesNotContain("must-not-be-logged", "/webhooks/whatsapp");
    }

    @Test
    void theScriptPinsTheImageAndListensOnThisMachineOnly() throws IOException {
        String script = Files.readString(SCRIPTS.resolve("gate"));

        assertThat(pinnedImage())
                .matches(
                        "public\\.ecr\\.aws/docker/library/nginx:[0-9]+\\.[0-9]+\\.[0-9]+-[a-z0-9.-]+@sha256:[0-9a-f]{64}");
        assertThat(script)
                .contains("--publish \"127.0.0.1:${filter_port}:8088\"")
                .contains("--read-only", "--cap-drop ALL", "--security-opt no-new-privileges", "--user 101:101")
                .contains("webhook-only.conf:/etc/nginx/nginx.conf:ro");
        assertThat(script.split("--publish", -1)).as("one published port").hasSize(2);
        assertThat(Files.readString(SCRIPTS.resolve("webhook-only.conf")).split("proxy_pass ", -1))
                .as("one address it can ever ask for")
                .hasSize(2);
    }

    /** The image the script runs, which is what is tested here. */
    private static String pinnedImage() throws IOException {
        Matcher image =
                Pattern.compile("(?m)^nginx_image=\"([^\"]+)\"$").matcher(Files.readString(SCRIPTS.resolve("gate")));
        assertThat(image.find()).as("nginx_image in scripts/local/gate").isTrue();
        return image.group(1);
    }

    private record Answer(int status, String body) {}

    private static Answer send(String method, String target) throws IOException {
        return send(filter, method, target, null);
    }

    private static Answer send(String method, String target, byte[] body, String... headers) throws IOException {
        return send(filter, method, target, body, headers);
    }

    /** One request, its line and headers written to the socket exactly as given. */
    private static Answer send(GenericContainer<?> to, String method, String target, byte[] body, String... headers)
            throws IOException {
        try (Socket socket = new Socket(to.getHost(), to.getMappedPort(8088))) {
            socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());
            StringBuilder head = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: gate\r\n");
            for (String header : headers) head.append(header).append("\r\n");
            if (body != null)
                head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                try {
                    out.write(body);
                } catch (IOException refusedWhileSending) {
                    // Too large: nginx may answer before the whole body is sent.
                }
            }
            out.flush();
            return answer(socket.getInputStream());
        }
    }

    private static Answer answer(InputStream in) throws IOException {
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) read.write(buffer, 0, n);
        } catch (IOException resetAfterTheAnswer) {
            // The answer is already here.
        }
        String text = read.toString(StandardCharsets.UTF_8);
        int status = Integer.parseInt(text.substring(9, 12));
        int end = text.indexOf("\r\n\r\n");
        return new Answer(status, end < 0 ? "" : text.substring(end + 4));
    }
}
