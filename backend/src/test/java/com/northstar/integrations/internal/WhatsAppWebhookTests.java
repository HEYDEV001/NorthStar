package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.TestcontainersConfiguration.WhatsAppTestSecrets;
import com.northstar.fakes.FakeClock;
import com.northstar.fakes.WebhookDelivery;
import com.northstar.fakes.WebhookScript;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.WebhookRouting;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * {@code POST /webhooks/whatsapp} end to end (step 2.2), against real Postgres with RLS and signed by the fake kit:
 * verify the signature (P15), route by phone number ID (P16), store in the tenant's scope and dedupe (P1), queue a job,
 * answer 200. Also the handshake, the body limit, the security scope, the logs and the queued job.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppWebhookTests {

    private static final String PATH = "/webhooks/whatsapp";

    private final MockMvcTester mvc;
    private final WhatsAppTestSecrets secrets;
    private final WebhookScript script;
    private final WhatsAppWebhookIntake intake;
    private final WhatsAppWebhookSettings settings;
    private final WebhookRouting routing;
    private final UnroutedRateLimiter limiter;
    private final ProcessWhatsAppWebhookHandler handler;
    private final AppUserDatabase appUser;
    private final JdbcClient admin;
    private final TestData data;

    WhatsAppWebhookTests(
            @Autowired MockMvcTester mvc,
            @Autowired WhatsAppTestSecrets secrets,
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired WhatsAppWebhookSettings settings,
            @Autowired WebhookRouting routing,
            @Autowired UnroutedRateLimiter limiter,
            @Autowired ProcessWhatsAppWebhookHandler handler,
            @Autowired AppUserDatabase appUser,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.secrets = secrets;
        this.script = new WebhookScript(secrets.signer(), new FakeClock(Instant.parse("2026-11-19T10:00:00Z")));
        this.intake = intake;
        this.settings = settings;
        this.routing = routing;
        this.limiter = limiter;
        this.handler = handler;
        this.appUser = appUser;
        this.admin = postgres.jdbc();
        this.data = new TestData(postgres);
    }

    // P1: duplicates

    @Test
    void P1_duplicateWhatsAppDeliveryStoresAndQueuesOnce() {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        String wamid = WhatsAppPayloads.wamid();
        String body =
                WhatsAppPayloads.delivery().message(number, wamid, "Hello").json();

        for (WebhookDelivery delivery : script.twice(body, Duration.ofMinutes(1))) {
            assertThat(post(delivery)).hasStatusOk();
        }

        List<UUID> rows = events("message:" + wamid);
        assertThat(rows).hasSize(1);
        assertThat(tenantOf(rows.getFirst())).isEqualTo(tenant);
        assertThat(jobsFor(rows.getFirst())).isEqualTo(1);
        assertThat(jobsMentioning(wamid)).as("a job carries IDs only").isZero();
    }

    @Test
    void P1_sameItemRebatchedIsStillOneRow() {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        String first = WhatsAppPayloads.wamid();
        String second = WhatsAppPayloads.wamid();
        String outbound = WhatsAppPayloads.wamid();

        assertThat(post(script.deliver(
                        WhatsAppPayloads.delivery().message(number, first, "Hi").json())))
                .hasStatusOk();
        // A retry that Meta batched with new items: only the new ones are stored.
        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .messages(number, List.of(second, first), "Hi")
                        .status(number, outbound, "delivered")
                        .status(number, outbound, "delivered")
                        .status(number, outbound, "read")
                        .json())))
                .hasStatusOk();

        assertThat(events("message:" + first)).hasSize(1);
        assertThat(events("message:" + second)).hasSize(1);
        assertThat(events("status:" + outbound + ":delivered")).hasSize(1);
        assertThat(events("status:" + outbound + ":read")).hasSize(1);
        for (String key : List.of("message:" + first, "message:" + second, "status:" + outbound + ":read")) {
            assertThat(jobsFor(events(key).getFirst())).as(key).isEqualTo(1);
        }
    }

    @Test
    void P1_concurrentIdenticalDeliveriesStoreOnce() throws Exception {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        String wamid = WhatsAppPayloads.wamid();
        byte[] body = WhatsAppPayloads.delivery()
                .message(number, wamid, "At once")
                .json()
                .getBytes(StandardCharsets.UTF_8);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<WhatsAppWebhookIntake.Outcome>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return intake.receive(body);
                }));
            }
            start.countDown();
            int stored = 0;
            for (Future<WhatsAppWebhookIntake.Outcome> result : results)
                stored += result.get().stored();
            assertThat(stored).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(events("message:" + wamid)).hasSize(1);
        assertThat(jobsFor(events("message:" + wamid).getFirst())).isEqualTo(1);
    }

    // P15: bad signatures

    @Test
    void P15_invalidSignatureIsRefusedAndParkedWithoutPayload() {
        assertRefusedAndParked(script.withInvalidSignature(validBodyWithMarker()));
    }

    @Test
    void P15_missingSignatureIsRefusedAndParkedWithoutPayload() {
        assertRefusedAndParked(script.unsigned(validBodyWithMarker()));
    }

    @Test
    void P15_signatureOverOtherBytesIsRefusedAndParkedWithoutPayload() {
        String body = validBodyWithMarker();
        WebhookDelivery signed = script.deliver(body);
        // The same JSON, re-spaced: the signature covers the exact bytes, so it no longer matches.
        String reformatted = body.replace(",", ", ");
        assertRefusedAndParked(new WebhookDelivery(
                reformatted.getBytes(StandardCharsets.UTF_8),
                signed.headers(),
                signed.occurredAt(),
                signed.deliveredAt()));
    }

    @Test
    void P15_malformedSignatureHeadersAreRefused() {
        String body = validBodyWithMarker();
        String good = script.deliver(body).headers().get(WebhookSignature.WHATSAPP_HEADER);
        for (String header : List.of(good.toUpperCase(), good.substring(7), "sha1=" + good.substring(7), good + "0")) {
            WebhookDelivery bad = script.unsigned(body).withHeader(WebhookSignature.WHATSAPP_HEADER, header);
            assertThat(post(bad)).as(header).hasStatus(401);
        }
        assertThat(eventsMentioning(body)).isZero();
    }

    private void assertRefusedAndParked(WebhookDelivery delivery) {
        long before = unroutedCount("invalid_signature");
        MvcTestResult result = post(delivery);

        assertThat(result).hasStatus(401);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("invalid_signature");
        assertThat(unroutedCount("invalid_signature")).isEqualTo(before + 1);
        Map<String, Object> row = admin.sql("""
                        select provider, provider_event_id, routing_key, payload::text as payload, body_bytes,
                               signature_valid, payload_too_large
                        from ingest.unrouted_webhooks where reason = 'invalid_signature'
                        order by received_at desc limit 1""").query().singleRow();
        assertThat(row)
                .containsEntry("provider", "whatsapp")
                .containsEntry("signature_valid", false)
                .containsEntry("body_bytes", delivery.body().length)
                .containsEntry("payload_too_large", false);
        assertThat(row.get("provider_event_id"))
                .as("never parsed from an unverified body")
                .isNull();
        assertThat(row.get("routing_key")).isNull();
        assertThat(row.get("payload")).isNull();
        assertThat(eventsMentioning(delivery.bodyText()))
                .as("nothing routed or stored")
                .isZero();
    }

    // P16: unknown phone number IDs

    @Test
    void P16_unknownPhoneNumberIdIsParkedWithPayload(CapturedOutput output) {
        String unknown = WhatsAppPayloads.phoneNumberId();
        String wamid = WhatsAppPayloads.wamid();
        String marker = WhatsAppPayloads.marker();

        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .message(unknown, wamid, marker)
                        .json())))
                .hasStatusOk();

        Map<String, Object> row = unroutedByEvent("message:" + wamid);
        assertThat(row)
                .containsEntry("reason", "unknown_account")
                .containsEntry("signature_valid", true)
                .containsEntry("routing_key", unknown)
                .containsEntry("payload_too_large", false);
        assertThat((String) row.get("payload")).contains(marker).contains(wamid);
        assertThat(eventsMentioning(wamid)).as("nothing stored in any tenant").isZero();
        assertThat(output.getAll()).contains("webhook.unknown_account provider=whatsapp unrouted_id=" + row.get("id"));
        assertThat(warnLine(output, "webhook.unknown_account")).contains("WARN");
    }

    @Test
    void P16_unknownRetryIsParkedOnce() {
        String unknown = WhatsAppPayloads.phoneNumberId();
        String wamid = WhatsAppPayloads.wamid();
        for (WebhookDelivery delivery : script.twice(
                WhatsAppPayloads.delivery().message(unknown, wamid, "Hi").json(), Duration.ofMinutes(5))) {
            assertThat(post(delivery)).hasStatusOk();
        }
        assertThat(admin.sql("select count(*) from ingest.unrouted_webhooks where provider_event_id = :e")
                        .param("e", "message:" + wamid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void P16_mixedBatchRoutesTheKnownAndParksOnlyTheUnknownSlice() {
        UUID tenant = data.tenant();
        String known = data.whatsAppNumber(tenant);
        String unknown = WhatsAppPayloads.phoneNumberId();
        String knownWamid = WhatsAppPayloads.wamid();
        String unknownWamid = WhatsAppPayloads.wamid();
        String knownMarker = WhatsAppPayloads.marker();

        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .message(known, knownWamid, knownMarker)
                        .message(unknown, unknownWamid, "Elsewhere")
                        .inOneEntry()
                        .json())))
                .hasStatusOk();

        assertThat(events("message:" + knownWamid)).hasSize(1);
        Map<String, Object> parked = unroutedByEvent("message:" + unknownWamid);
        String payload = (String) parked.get("payload");
        assertThat(payload)
                .contains(unknownWamid)
                .doesNotContain(known)
                .doesNotContain(knownWamid)
                .doesNotContain(knownMarker);
        assertThat((Integer) parked.get("body_bytes")).isLessThan(payload.length() + 200);
    }

    @Test
    void P16_oversizedUnknownPayloadIsParkedWithoutIt() {
        String unknown = WhatsAppPayloads.phoneNumberId();
        String wamid = WhatsAppPayloads.wamid();
        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .message(unknown, wamid, "x".repeat(70_000))
                        .json())))
                .hasStatusOk();

        Map<String, Object> row = unroutedByEvent("message:" + wamid);
        assertThat(row.get("payload")).isNull();
        assertThat(row).containsEntry("payload_too_large", true).containsEntry("routing_key", unknown);
        assertThat((Integer) row.get("body_bytes")).isGreaterThan(65_536);
    }

    // P9: one delivery, two tenants

    @Test
    void P9_whatsappBatchForTwoTenantsLandsInEachTenantOnly() {
        UUID tenantA = data.tenant();
        UUID tenantB = data.tenant();
        String numberA = data.whatsAppNumber(tenantA);
        String numberB = data.whatsAppNumber(tenantB);
        String markerA = WhatsAppPayloads.marker();
        String markerB = WhatsAppPayloads.marker();
        String wamidA = WhatsAppPayloads.wamid();
        String wamidB = WhatsAppPayloads.wamid();
        String statusA = WhatsAppPayloads.wamid();

        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .message(numberA, wamidA, markerA)
                        .message(numberB, wamidB, markerB)
                        .status(numberA, statusA, "sent")
                        .inOneEntry()
                        .json())))
                .hasStatusOk();

        for (Side side : List.of(
                new Side(tenantA, List.of(wamidA, statusA), markerA, List.of(wamidB, markerB, numberB)),
                new Side(tenantB, List.of(wamidB), markerB, List.of(wamidA, statusA, markerA, numberA)))) {
            List<Map<String, Object>> seen = appUser.as(
                    side.tenant(),
                    TenantContext.SYSTEM_USER_ID,
                    "system",
                    jdbc -> jdbc.sql(
                                    "select id, tenant_id, provider_event_id, payload::text as payload from northstar.webhook_events"
                                            + " where provider_event_id like '%wamid.TEST%'")
                            .query()
                            .listOfRows());
            assertThat(seen).extracting(row -> row.get("tenant_id")).containsOnly(side.tenant());
            List<String> keys = seen.stream()
                    .map(row -> (String) row.get("provider_event_id"))
                    .toList();
            for (String own : side.own()) assertThat(keys).anyMatch(key -> key.contains(own));
            for (Map<String, Object> row : seen) {
                String payload = (String) row.get("payload");
                for (String foreign : side.foreign()) assertThat(payload).doesNotContain(foreign);
                assertThat(jobTenant((UUID) row.get("id"))).isEqualTo(side.tenant());
            }
            assertThat(seen.stream().map(row -> (String) row.get("payload")))
                    .anyMatch(payload -> payload.contains(side.marker()));
        }
        // The other tenant's rows are invisible, not merely filtered: a direct read by key finds nothing.
        assertThat(appUser.<Long>as(
                        tenantA,
                        TenantContext.SYSTEM_USER_ID,
                        "system",
                        jdbc -> jdbc.sql("select count(*) from northstar.webhook_events where provider_event_id = ?")
                                .param("message:" + wamidB)
                                .query(Long.class)
                                .single()))
                .isZero();
    }

    private record Side(UUID tenant, List<String> own, String marker, List<String> foreign) {}

    // What is stored, and what is only counted

    @Test
    void otherFieldsAndIdlessErrorsAreIgnoredWithACount(CapturedOutput output) {
        String number = data.whatsAppNumber(data.tenant());
        String body = WhatsAppPayloads.delivery()
                .otherField("message_template_status_update")
                .otherField("account_update")
                .errorsOnly(number)
                .json();
        long before = totalEvents();
        assertThat(post(script.deliver(body))).hasStatusOk();
        assertThat(totalEvents()).isEqualTo(before);
        assertThat(output.getAll())
                .contains("webhook.received provider=whatsapp items=0 stored=0")
                .contains("ignored=3");
    }

    @Test
    void aSignedBodyThatIsNotJsonOrNotWhatsAppIsAcceptedStoredNowhereAndLoggedOnce(CapturedOutput output) {
        long events = totalEvents();
        long parked = unroutedCount("unknown_account") + unroutedCount("invalid_signature");
        for (String body : List.of("not json at all", "{\"object\":\"page\",\"entry\":[]}")) {
            assertThat(post(script.deliver(body))).as(body).hasStatusOk();
        }
        assertThat(totalEvents()).isEqualTo(events);
        assertThat(unroutedCount("unknown_account") + unroutedCount("invalid_signature"))
                .isEqualTo(parked);
        assertThat(output.getAll().lines().filter(l -> l.contains("webhook.unreadable")))
                .hasSize(2)
                .allMatch(l -> l.contains("ERROR"));
    }

    @Test
    void aMessageWithNulCharactersIsStored() {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        String wamid = WhatsAppPayloads.wamid();
        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .message(number, wamid, "a\u0000b")
                        .json())))
                .hasStatusOk();
        assertThat(events("message:" + wamid)).hasSize(1);
    }

    // The handshake

    @Test
    void theHandshakeEchoesTheChallengeForTheRightTokenOnly() {
        MvcTestResult ok = mvc.get()
                .uri(PATH)
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", secrets.verifyToken())
                .param("hub.challenge", "1158201444")
                .exchange();
        assertThat(ok).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_PLAIN);
        assertThat(ok).hasBodyTextEqualTo("1158201444");
        assertThat(ok).hasHeader("X-Request-Id", ok.getResponse().getHeader("X-Request-Id"));
        assertThat(ok.getResponse().getHeader("X-Request-Id")).isNotBlank();

        List<Map<String, String>> refused = List.of(
                Map.of("hub.mode", "subscribe", "hub.verify_token", secrets.verifyToken() + "x", "hub.challenge", "1"),
                Map.of("hub.mode", "subscribe", "hub.challenge", "1"),
                Map.of("hub.mode", "unsubscribe", "hub.verify_token", secrets.verifyToken(), "hub.challenge", "1"),
                Map.of("hub.verify_token", secrets.verifyToken(), "hub.challenge", "1"),
                Map.of("hub.mode", "subscribe", "hub.verify_token", secrets.verifyToken(), "hub.challenge", "<b>x</b>"),
                Map.of("hub.mode", "subscribe", "hub.verify_token", secrets.verifyToken()));
        for (Map<String, String> params : refused) {
            var request = mvc.get().uri(PATH);
            params.forEach(request::param);
            MvcTestResult result = request.exchange();
            assertThat(result).as(params.toString()).hasStatus(403);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("verification_failed");
        }
    }

    // Body limits

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsReadOrStored() {
        long events = totalEvents();
        long parked = unroutedCount("invalid_signature");
        byte[] huge = new byte[3 * 1024 * 1024 + 1];
        java.util.Arrays.fill(huge, (byte) ' ');
        MvcTestResult result = mvc.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(huge)
                .exchange();
        assertThat(result).hasStatus(413);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("payload_too_large");
        assertThat(totalEvents()).isEqualTo(events);
        assertThat(unroutedCount("invalid_signature")).as("not parked").isEqualTo(parked);
    }

    @Test
    void aStreamWithNoLengthIsCutOffAtTheLimit() throws Exception {
        WhatsAppWebhookController small = new WhatsAppWebhookController(settings, intake, routing, limiter, 100);
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", PATH) {
            @Override
            public long getContentLengthLong() {
                return -1; // as with Transfer-Encoding: chunked
            }
        };
        chunked.setContent(new byte[101]);
        assertThatThrownBy(() -> small.receive(chunked))
                .isInstanceOfSatisfying(
                        WebhookRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("payload_too_large"));

        MockHttpServletRequest withinLimit = new MockHttpServletRequest("POST", PATH);
        withinLimit.setContent(new byte[100]);
        assertThatThrownBy(() -> small.receive(withinLimit))
                .as("read in full, then the signature is checked")
                .isInstanceOfSatisfying(
                        WebhookRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("invalid_signature"));
    }

    // Security scope

    @Test
    void onlyTheWebhookMethodsAndPathArePublicAndATokenIsIgnored() {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        String wamid = WhatsAppPayloads.wamid();
        WebhookDelivery delivery = script.deliver(
                        WhatsAppPayloads.delivery().message(number, wamid, "Hi").json())
                .withHeader(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token");
        assertThat(post(delivery))
                .as("a stray bearer token is ignored, never verified")
                .hasStatusOk();
        assertThat(events("message:" + wamid)).hasSize(1);

        assertThat(mvc.put()
                        .uri(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .exchange())
                .hasStatus(401);
        assertThat(mvc.delete().uri(PATH).exchange()).hasStatus(401);
        assertThat(mvc.post()
                        .uri(PATH + "/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .exchange())
                .hasStatus(401);
        assertThat(mvc.post()
                        .uri("/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .exchange())
                .hasStatus(401);
        assertThat(mvc.post()
                        .uri("/api/v1/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .exchange())
                .hasStatus(401);
        assertThat(mvc.get().uri("/api/v1/me").exchange()).hasStatus(401);
    }

    // Logs

    @Test
    void nothingSensitiveIsLogged(CapturedOutput output) {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        data.workflowVersion(tenant); // so the job opens a lead, the fullest path
        String unknown = WhatsAppPayloads.phoneNumberId();
        String wamid = WhatsAppPayloads.wamid();
        String unknownWamid = WhatsAppPayloads.wamid();
        String marker = WhatsAppPayloads.marker();
        String body = WhatsAppPayloads.delivery()
                .message(number, wamid, marker)
                .message(unknown, unknownWamid, marker)
                .json();
        WebhookDelivery signed = script.deliver(body);
        String signature = signed.headers().get(WebhookSignature.WHATSAPP_HEADER);

        assertThat(post(signed)).hasStatusOk();
        assertThat(post(script.withInvalidSignature(body))).hasStatus(401);
        assertThat(mvc.get()
                        .uri(PATH)
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", secrets.verifyToken())
                        .param("hub.challenge", "987654321123")
                        .exchange())
                .hasStatusOk();
        assertThat(mvc.get()
                        .uri(PATH)
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong-" + secrets.verifyToken())
                        .param("hub.challenge", "987654321123")
                        .exchange())
                .hasStatus(403);
        UUID stored = events("message:" + wamid).getFirst();
        runJob(new ProcessWhatsAppWebhook(tenant, stored));

        String logs = output.getAll();
        assertThat(logs).contains("webhook.received").contains("webhook.stored").contains("webhook.processed");
        for (String secret : List.of(
                marker,
                wamid,
                unknownWamid,
                number,
                unknown,
                "910000000001",
                "Test Customer",
                signature,
                signature.substring("sha256=".length()),
                secrets.signer().secret(),
                secrets.verifyToken(),
                "987654321123")) {
            assertThat(logs).as("logs must not contain it").doesNotContain(secret);
        }
    }

    // The queued job

    @Test
    void theQueuedJobProcessesItsRowInItsTenantOnly(CapturedOutput output) {
        UUID tenant = data.tenant();
        UUID otherTenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        data.workflowVersion(tenant);
        String wamid = WhatsAppPayloads.wamid();
        assertThat(post(script.deliver(
                        WhatsAppPayloads.delivery().message(number, wamid, "Hi").json())))
                .hasStatusOk();
        UUID id = events("message:" + wamid).getFirst();

        // In another tenant the row is invisible: the job fails loudly rather than doing anything (step 2.4b covers
        // the processing itself in InboundWhatsAppJobTests).
        assertThatThrownBy(() -> runJob(new ProcessWhatsAppWebhook(otherTenant, id)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(output.getAll()).contains("webhook.event_missing webhook_event_id=" + id);

        runJob(new ProcessWhatsAppWebhook(tenant, id));
        assertThat(output.getAll()).contains("webhook.processed provider=whatsapp webhook_event_id=" + id);
        assertThat(admin.sql("select processed_at is not null and error is null from northstar.webhook_events"
                                + " where id = :id")
                        .param("id", id)
                        .query(Boolean.class)
                        .single())
                .as("processed in full")
                .isTrue();
        assertThat(admin.sql(
                                "select count(*) from northstar.messages where tenant_id = :t and provider_message_id = :w")
                        .param("t", tenant)
                        .param("w", wamid)
                        .query(Long.class)
                        .single())
                .isOne();
    }

    // Helpers

    private MvcTestResult post(WebhookDelivery delivery) {
        var request =
                mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(delivery.body());
        delivery.headers().forEach((name, value) -> request.header(name, value));
        return request.exchange();
    }

    private String validBodyWithMarker() {
        String number = data.whatsAppNumber(data.tenant());
        return WhatsAppPayloads.delivery()
                .message(number, WhatsAppPayloads.wamid(), WhatsAppPayloads.marker())
                .json();
    }

    private List<UUID> events(String providerEventId) {
        return admin.sql(
                        "select id from northstar.webhook_events where provider = 'whatsapp' and provider_event_id = :e")
                .param("e", providerEventId)
                .query(UUID.class)
                .list();
    }

    private long eventsMentioning(String text) {
        // Any wamid or marker from the body appearing in a stored event.
        long count = 0;
        for (String token : text.split("\"")) {
            if (token.startsWith("wamid.TEST") || token.startsWith("body-marker-")) {
                count += admin.sql("select count(*) from northstar.webhook_events where payload::text like :t")
                        .param("t", "%" + token + "%")
                        .query(Long.class)
                        .single();
            }
        }
        return count;
    }

    private long totalEvents() {
        return admin.sql("select count(*) from northstar.webhook_events")
                .query(Long.class)
                .single();
    }

    private UUID tenantOf(UUID event) {
        return admin.sql("select tenant_id from northstar.webhook_events where id = :id")
                .param("id", event)
                .query(UUID.class)
                .single();
    }

    private long unroutedCount(String reason) {
        return admin.sql("select count(*) from ingest.unrouted_webhooks where reason = :r")
                .param("r", reason)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> unroutedByEvent(String providerEventId) {
        return admin.sql("""
                        select id, reason, signature_valid, routing_key, payload::text as payload, body_bytes,
                               payload_too_large
                        from ingest.unrouted_webhooks where provider_event_id = :e""").param("e", providerEventId).query().singleRow();
    }

    private long jobsFor(UUID webhookEventId) {
        return admin.sql("select count(*) from jobrunr.jobrunr_jobs where id = :id")
                .param("id", ProcessWhatsAppWebhook.jobIdFor(webhookEventId).toString())
                .query(Long.class)
                .single();
    }

    private long jobsMentioning(String text) {
        return admin.sql("select count(*) from jobrunr.jobrunr_jobs where jobasjson like :t")
                .param("t", "%" + text + "%")
                .query(Long.class)
                .single();
    }

    private UUID jobTenant(UUID webhookEventId) {
        String json = admin.sql("select jobasjson from jobrunr.jobrunr_jobs where id = :id")
                .param("id", ProcessWhatsAppWebhook.jobIdFor(webhookEventId).toString())
                .query(String.class)
                .single();
        assertThat(json).contains(webhookEventId.toString());
        int at = json.indexOf("\"tenantId\"");
        assertThat(at).as("the job names its tenant").isPositive();
        String rest = json.substring(at);
        java.util.regex.Matcher uuid = java.util.regex.Pattern.compile(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .matcher(rest);
        assertThat(uuid.find()).isTrue();
        return UUID.fromString(uuid.group());
    }

    /**
     * Runs the job as the worker does: Spring's test support binds a mock request to the test thread, and the system
     * actor is refused on a thread serving a request, so the request is lifted while the job runs.
     */
    private void runJob(ProcessWhatsAppWebhook request) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            handler.run(request);
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    private static String warnLine(CapturedOutput output, String event) {
        return output.getAll()
                .lines()
                .filter(l -> l.contains(event))
                .findFirst()
                .orElse("");
    }
}
