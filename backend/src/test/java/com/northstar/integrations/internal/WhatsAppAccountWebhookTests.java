package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.TestcontainersConfiguration.WhatsAppTestSecrets;
import com.northstar.fakes.FakeClock;
import com.northstar.fakes.WebhookDelivery;
import com.northstar.fakes.WebhookScript;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.jobs.JobTenantException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Webhook changes that name only their business account (step 2.8a, integration-specs.md section 2), end to end
 * against real Postgres with RLS: {@code business_capability_update} is verified, routed by the WABA ID in
 * {@code entry.id} (P9; P16 for an account no tenant has), stored once per change however Meta batches it (P1), and
 * processed into the messaging limit of every number of that account in its tenant; a limit Meta does not document is
 * not stored. Also the job's checks (P9, P10), unusable rows, and the logs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppAccountWebhookTests {

    private static final String PATH = "/webhooks/whatsapp";
    private static final long TIME = 1_739_321_024L;

    private final MockMvcTester mvc;
    private final WebhookScript script;
    private final ProcessWhatsAppWebhookHandler job;
    private final JdbcClient admin;
    private final TestData data;

    WhatsAppAccountWebhookTests(
            @Autowired MockMvcTester mvc,
            @Autowired WhatsAppTestSecrets secrets,
            @Autowired ProcessWhatsAppWebhookHandler job,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.script = new WebhookScript(secrets.signer(), new FakeClock(Instant.parse("2026-11-22T10:00:00Z")));
        this.job = job;
        this.admin = postgres.jdbc();
        this.data = new TestData(postgres);
    }

    // Routing by WABA ID

    @Test
    void P9_limitChangesOfTwoTenantsAccountsLandInEachTenantOnly() {
        Account a = account(2);
        Account b = account(1);
        String wamid = WhatsAppPayloads.wamid();

        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .capabilityUpdate(a.waba(), TIME, "TIER_10K")
                        .message(a.numbers().getFirst(), wamid, "Hello")
                        .capabilityUpdate(b.waba(), TIME, "TIER_100K")
                        .json())))
                .hasStatusOk();

        UUID rowA = row(a.waba());
        UUID rowB = row(b.waba());
        assertThat(tenantOf(rowA)).isEqualTo(a.tenant());
        assertThat(tenantOf(rowB)).isEqualTo(b.tenant());
        assertThat(payload(rowA)).contains(a.waba()).doesNotContain(b.waba()).doesNotContain(wamid);
        assertThat(payload(rowB)).contains(b.waba()).doesNotContain(a.waba());
        assertThat(jobExists(rowA)).isTrue();
        assertThat(jobExists(rowB)).isTrue();

        runJob(a.tenant(), rowA);
        runJob(b.tenant(), rowB);

        assertThat(limits(a)).containsOnly("tier_10k");
        assertThat(limits(b)).containsOnly("tier_100k");
    }

    @Test
    void P16_anUnknownAccountIsParkedWithItsWabaIdAndOnlyItsOwnSlice(CapturedOutput output) {
        Account known = account(1);
        String unknown = WhatsAppPayloads.wabaId();

        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .capabilityUpdate(known.waba(), TIME, "TIER_2K")
                        .capabilityUpdate(unknown, TIME, "TIER_250")
                        .json())))
                .hasStatusOk();

        assertThat(row(known.waba())).isNotNull();
        Map<String, Object> parked = admin.sql("""
                        select id, reason, signature_valid, routing_key, payload::text as payload, provider_event_id
                        from ingest.unrouted_webhooks where routing_key = :k""").param("k", unknown).query().singleRow();
        assertThat(parked)
                .containsEntry("reason", "unknown_account")
                .containsEntry("signature_valid", true)
                .containsEntry("routing_key", unknown);
        assertThat((String) parked.get("provider_event_id"))
                .startsWith("business_capability_update:" + unknown + ":" + TIME + ":");
        assertThat((String) parked.get("payload")).contains(unknown).doesNotContain(known.waba());
        assertThat(eventsNaming(unknown)).as("nothing stored in any tenant").isZero();
        assertThat(output.getAll())
                .contains("webhook.unknown_account provider=whatsapp unrouted_id=" + parked.get("id"))
                .doesNotContain(unknown);
    }

    @Test
    void P1_aRepeatedLimitChangeIsStoredOnceHoweverItIsBatched() {
        Account account = account(1);
        Account other = account(1);
        String alone = WhatsAppPayloads.delivery()
                .capabilityUpdate(account.waba(), TIME, "TIER_2K")
                .json();
        for (WebhookDelivery delivery : script.twice(alone, Duration.ofMinutes(1))) {
            assertThat(post(delivery)).hasStatusOk();
        }
        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .capabilityUpdate(other.waba(), TIME, "TIER_2K")
                        .capabilityUpdate(account.waba(), TIME, "TIER_2K")
                        .json())))
                .hasStatusOk();

        assertThat(rows(account.waba())).as("one row, one job").hasSize(1);
        assertThat(rows(other.waba())).hasSize(1);
    }

    @Test
    void P1_processingTheSameRowAgainChangesNothing(CapturedOutput output) {
        Account account = account(1);
        post(script.deliver(WhatsAppPayloads.delivery()
                .capabilityUpdate(account.waba(), TIME, "TIER_2K")
                .json()));
        UUID row = row(account.waba());

        runJob(account.tenant(), row);
        String updatedAt = updatedAt(account.numbers().getFirst());
        runJob(account.tenant(), row);

        assertThat(output.getAll()).contains("webhook.already_processed provider=whatsapp webhook_event_id=" + row);
        assertThat(updatedAt(account.numbers().getFirst())).isEqualTo(updatedAt);
        assertThat(limits(account)).containsOnly("tier_2k");
    }

    // Processing

    @Test
    void theLimitIsSetOnEveryNumberOfThatAccountAndOnNoOther(CapturedOutput output) {
        Account account = account(2);
        String otherWaba = WhatsAppPayloads.wabaId();
        String otherNumber = data.whatsAppNumber(account.tenant(), otherWaba);
        setLimit(account.numbers().getFirst(), "tier_2k"); // already right: unchanged

        UUID row = deliverAndRun(account, "TIER_2K");

        assertThat(limits(account)).containsOnly("tier_2k");
        assertThat(limit(otherNumber))
                .as("another account of the same business")
                .isNull();
        assertThat(processing(row)).isEqualTo("true|");
        assertThat(output.getAll())
                .contains("webhook.limit_applied provider=whatsapp webhook_event_id=" + row
                        + " limit=tier_2k numbers=2 changed=1");
    }

    @Test
    void aLaterLimitReplacesAnEarlierOne() {
        Account account = account(1);
        deliverAndRun(account, "TIER_2K");

        post(script.deliver(WhatsAppPayloads.delivery()
                .capabilityUpdate(account.waba(), TIME + 3600, "TIER_10K")
                .json()));
        UUID later = rows(account.waba()).stream()
                .filter(id -> processing(id).startsWith("false"))
                .findFirst()
                .orElseThrow();
        runJob(account.tenant(), later);

        assertThat(limits(account)).containsOnly("tier_10k");
    }

    @Test
    void everyLimitMetaDocumentsIsStored() {
        Map<String, String> limits = Map.of(
                "TIER_50", "tier_50",
                "TIER_250", "tier_250",
                "TIER_2K", "tier_2k",
                "TIER_10K", "tier_10k",
                "TIER_100K", "tier_100k",
                "TIER_UNLIMITED", "tier_unlimited",
                "TIER_NOT_SET", "tier_not_set");
        limits.forEach((meta, ours) -> {
            Account account = account(1);
            deliverAndRun(account, meta);
            assertThat(limits(account)).as(meta).containsOnly(ours);
        });
    }

    @Test
    void aLimitMetaDoesNotDocumentIsNotStoredAndTheRowIsClosedWithACode(CapturedOutput output) {
        // 2000 is the value in Meta's own example payload, though the field's documented values are TIER_ names.
        for (String body : List.of("\"TIER_1K\"", "2000", "\"tier_2k\"", "true")) {
            Account account = account(1);
            setLimit(account.numbers().getFirst(), "tier_250");
            String json = WhatsAppPayloads.delivery()
                    .capabilityUpdate(account.waba(), TIME, "PLACEHOLDER")
                    .json()
                    .replace("\"PLACEHOLDER\"", body);
            post(script.deliver(json));
            UUID row = row(account.waba());

            runJob(account.tenant(), row);

            assertThat(limits(account)).as(body).containsOnly("tier_250");
            assertThat(processing(row)).as(body).isEqualTo("true|messaging_limit_unrecognised");
            assertThat(output.getAll())
                    .contains("webhook.limit_unrecognised provider=whatsapp webhook_event_id=" + row
                            + " error=messaging_limit_unrecognised")
                    .doesNotContain("TIER_1K");
        }
    }

    @Test
    void aChangeWithoutTheLimitChangesNothing(CapturedOutput output) {
        Account account = account(1);
        setLimit(account.numbers().getFirst(), "tier_10k");
        post(script.deliver(WhatsAppPayloads.delivery()
                .capabilityUpdate(account.waba(), TIME, null)
                .json()));
        UUID row = row(account.waba());

        runJob(account.tenant(), row);

        assertThat(limits(account)).containsOnly("tier_10k");
        assertThat(processing(row)).isEqualTo("true|");
        assertThat(output.getAll()).contains("webhook.limit_missing provider=whatsapp webhook_event_id=" + row);
    }

    @Test
    void anUnusableSliceIsClosedAsPayloadInvalidAndAnUnknownKindAsEventUnsupported() {
        Account account = account(1);
        String key = "business_capability_update:" + account.waba() + ":" + TIME + ":" + "0".repeat(32);
        UUID mismatched = insertRow(
                account.tenant(),
                key,
                WhatsAppPayloads.delivery()
                        .capabilityUpdate(account.waba(), TIME + 1, "TIER_2K")
                        .json());
        UUID empty = insertRow(account.tenant(), key.replace(":" + TIME + ":", ":" + (TIME + 2) + ":"), "{}");
        UUID unknownKind = insertRow(account.tenant(), "account_update:" + UUID.randomUUID(), "{}");

        runJob(account.tenant(), mismatched);
        runJob(account.tenant(), empty);
        runJob(account.tenant(), unknownKind);

        assertThat(processing(mismatched)).as("a slice that is not its key's").isEqualTo("true|payload_invalid");
        assertThat(processing(empty)).isEqualTo("true|payload_invalid");
        assertThat(processing(unknownKind))
                .as("closed, so the sweep never retries it forever")
                .isEqualTo("true|event_unsupported");
        assertThat(limits(account)).containsOnly((String) null);
    }

    // The job's tenant (P9, P10)

    @Test
    void P9_aRowNamingAnotherTenantsAccountChangesNothingThere() {
        Account a = account(1);
        Account b = account(1);
        setLimit(b.numbers().getFirst(), "tier_250");
        // Routing never stores this; the processor must still change nothing outside its own tenant.
        String slice = WhatsAppPayloads.delivery()
                .capabilityUpdate(b.waba(), TIME, "TIER_UNLIMITED")
                .json();
        String key = WhatsAppDelivery.parse(slice.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .items()
                .getFirst()
                .providerEventId();
        UUID row = insertRow(a.tenant(), key, slice);

        runJob(a.tenant(), row);

        assertThat(processing(row)).isEqualTo("true|account_not_found");
        assertThat(limits(b)).containsOnly("tier_250");
        assertThat(limits(a)).containsOnly((String) null);
    }

    @Test
    void P9_aJobNamingAnotherTenantsRowFailsLoudlyAndProcessesNothing() {
        Account a = account(1);
        Account b = account(1);
        post(script.deliver(WhatsAppPayloads.delivery()
                .capabilityUpdate(a.waba(), TIME, "TIER_2K")
                .json()));
        UUID row = row(a.waba());

        assertThatThrownBy(() -> runJob(b.tenant(), row))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Webhook event not found in the job's tenant");

        assertThat(processing(row)).isEqualTo("false|");
        assertThat(limits(a)).containsOnly((String) null);
    }

    @Test
    void P10_aJobWithoutATenantProcessesNothing() {
        Account account = account(1);
        post(script.deliver(WhatsAppPayloads.delivery()
                .capabilityUpdate(account.waba(), TIME, "TIER_2K")
                .json()));
        UUID row = row(account.waba());

        assertThatThrownBy(() -> runJob(null, row)).isInstanceOf(JobTenantException.class);

        assertThat(processing(row)).isEqualTo("false|");
        assertThat(limits(account)).containsOnly((String) null);
    }

    @Test
    void logsCarryIdsCountsAndCodesOnly(CapturedOutput output) {
        Account account = account(1);
        deliverAndRun(account, "TIER_2K");

        assertThat(output.getAll())
                .contains("webhook.received provider=whatsapp items=1 stored=1")
                .contains("webhook.limit_applied")
                .doesNotContain(account.waba())
                .doesNotContain(account.numbers().getFirst())
                .doesNotContain("TIER_2K");
    }

    // Helpers

    /** A business's account: its tenant, its WABA ID and its numbers' phone number IDs. */
    private record Account(UUID tenant, String waba, List<String> numbers) {}

    private Account account(int numbers) {
        UUID tenant = data.tenant();
        String waba = WhatsAppPayloads.wabaId();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < numbers; i++) ids.add(data.whatsAppNumber(tenant, waba));
        return new Account(tenant, waba, List.copyOf(ids));
    }

    private UUID deliverAndRun(Account account, String limit) {
        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .capabilityUpdate(account.waba(), TIME, limit)
                        .json())))
                .hasStatusOk();
        UUID row = row(account.waba());
        runJob(account.tenant(), row);
        return row;
    }

    private MvcTestResult post(WebhookDelivery delivery) {
        var request =
                mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(delivery.body());
        delivery.headers().forEach((name, value) -> request.header(name, value));
        return request.exchange();
    }

    /** The one stored row for this account's changes. */
    private UUID row(String waba) {
        List<UUID> rows = rows(waba);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<UUID> rows(String waba) {
        return admin.sql("""
                        select id from northstar.webhook_events
                        where provider = 'whatsapp' and provider_event_id like :prefix order by received_at, id""")
                .param("prefix", "business_capability_update:" + waba + ":%")
                .query(UUID.class)
                .list();
    }

    private UUID insertRow(UUID tenant, String key, String payload) {
        return admin.sql("""
                        insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)
                        values (:t, 'whatsapp', :key, cast(:payload as jsonb)) returning id""")
                .param("t", tenant)
                .param("key", key)
                .param("payload", payload)
                .query(UUID.class)
                .single();
    }

    private UUID tenantOf(UUID row) {
        return admin.sql("select tenant_id from northstar.webhook_events where id = :id")
                .param("id", row)
                .query(UUID.class)
                .single();
    }

    private String payload(UUID row) {
        return admin.sql("select payload::text from northstar.webhook_events where id = :id")
                .param("id", row)
                .query(String.class)
                .single();
    }

    /** processed|error */
    private String processing(UUID row) {
        return admin.sql("""
                        select (processed_at is not null) || '|' || coalesce(error, '')
                        from northstar.webhook_events where id = :id""").param("id", row).query(String.class).single();
    }

    private long eventsNaming(String text) {
        return admin.sql("select count(*) from northstar.webhook_events where payload::text like :t")
                .param("t", "%" + text + "%")
                .query(Long.class)
                .single();
    }

    private boolean jobExists(UUID row) {
        return admin.sql("select count(*) from jobrunr.jobrunr_jobs where id = :id")
                        .param("id", ProcessWhatsAppWebhook.jobIdFor(row).toString())
                        .query(Long.class)
                        .single()
                == 1;
    }

    private List<String> limits(Account account) {
        return account.numbers().stream().map(this::limit).toList();
    }

    private String limit(String phoneNumberId) {
        return admin.sql("select messaging_limit from northstar.whatsapp_accounts where phone_number_id = :n")
                .param("n", phoneNumberId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void setLimit(String phoneNumberId, String limit) {
        admin.sql("update northstar.whatsapp_accounts set messaging_limit = :l where phone_number_id = :n")
                .param("l", limit)
                .param("n", phoneNumberId)
                .update();
    }

    private String updatedAt(String phoneNumberId) {
        return admin.sql(
                        "select coalesce(updated_at::text, '') from northstar.whatsapp_accounts where phone_number_id = :n")
                .param("n", phoneNumberId)
                .query(String.class)
                .single();
    }

    /**
     * Runs the job as the worker does: Spring's test support binds a mock request to the test thread, and the system
     * actor is refused on a thread serving a request, so the request is lifted while the job runs.
     */
    private void runJob(UUID tenant, UUID row) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            job.run(new ProcessWhatsAppWebhook(tenant, row));
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }
}
