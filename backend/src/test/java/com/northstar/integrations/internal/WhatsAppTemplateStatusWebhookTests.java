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
import java.util.concurrent.atomic.AtomicLong;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Meta's template webhooks end to end (step 2.8b, integration-specs.md section 2, Template status from Meta), against
 * real Postgres with RLS: {@code message_template_status_update} and {@code template_category_update} are verified,
 * routed by the WABA ID, stored once (P1) and processed into the template Meta names, in Meta's order (an older one
 * changes nothing): every status event (W34), the events that leave the status, those that request a sync, templates
 * Northstar does not have, approved_at, category changes and notices, P9, P10, P16 and the logs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppTemplateStatusWebhookTests {

    private static final String PATH = "/webhooks/whatsapp";
    private static final long T0 = Instant.parse("2026-11-22T10:00:00Z").getEpochSecond();
    private static final AtomicLong IDS = new AtomicLong(1_387_372_356_000_000L + System.nanoTime() % 1_000_000L);

    private final MockMvcTester mvc;
    private final WebhookScript script;
    private final ProcessWhatsAppWebhookHandler job;
    private final JdbcClient admin;
    private final TestData data;

    WhatsAppTemplateStatusWebhookTests(
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

    // Statuses (W34)

    @Test
    void W34_templateStatusUpdatedFromMetaWebhook() {
        Account account = account();
        List<String[]> cases = List.of(
                new String[] {"APPROVED", "submitted", "approved"},
                new String[] {"REINSTATED", "disabled", "approved"},
                new String[] {"PENDING", "draft", "submitted"},
                new String[] {"IN_APPEAL", "rejected", "submitted"},
                new String[] {"PAUSED", "approved", "paused"},
                new String[] {"REJECTED", "submitted", "rejected"},
                new String[] {"LIMIT_EXCEEDED", "submitted", "rejected"},
                new String[] {"DISABLED", "paused", "disabled"},
                new String[] {"PENDING_DELETION", "approved", "deleted"},
                new String[] {"DELETED", "approved", "deleted"},
                new String[] {"ARCHIVED", "approved", "archived"});
        for (String[] each : cases) {
            Template template = template(account, each[1]);
            UUID row = deliver(account, statusChange(template, each[0], "NONE"), T0);
            runJob(account.tenant(), row);
            assertThat(status(template)).as(each[0]).isEqualTo(each[2]);
            assertThat(processing(row)).as(each[0]).isEqualTo("true|");
        }
    }

    @Test
    void flaggedAndLockedLeaveTheStatus(CapturedOutput output) {
        Account account = account();
        Template flagged = template(account, "approved");
        Template locked = template(account, "approved");

        UUID flaggedRow = deliver(account, statusChange(flagged, "FLAGGED", null), T0);
        UUID lockedRow = deliver(account, statusChange(locked, "LOCKED", null), T0);
        runJob(account.tenant(), flaggedRow);
        runJob(account.tenant(), lockedRow);

        assertThat(status(flagged)).isEqualTo("approved");
        assertThat(status(locked)).isEqualTo("approved");
        assertThat(output.getAll())
                .contains("webhook.template_flagged provider=whatsapp webhook_event_id=" + flaggedRow + " template_id="
                        + flagged.id())
                .contains("webhook.template_noted provider=whatsapp webhook_event_id=" + lockedRow + " template_id="
                        + locked.id() + " event=locked");
        assertThat(syncRequests(account.tenant())).isZero();
    }

    @Test
    void unarchivedLeavesTheStatusAndRequestsASync() {
        Account account = account();
        Template archived = template(account, "archived");

        UUID row = deliver(account, statusChange(archived, "UNARCHIVED", null), T0);
        runJob(account.tenant(), row);

        assertThat(status(archived))
                .as("Meta restores a status the event does not name")
                .isEqualTo("archived");
        assertThat(processing(row)).isEqualTo("true|");
        assertThat(syncRequests(account.tenant())).isOne();
    }

    @Test
    void anEventMetaDoesNotDocumentChangesNothingClosesTheRowAndRequestsASync(CapturedOutput output) {
        Account account = account();
        Template template = template(account, "paused");

        UUID row = deliver(account, statusChange(template, "UNPAUSED", null), T0);
        runJob(account.tenant(), row);

        assertThat(status(template)).isEqualTo("paused");
        assertThat(processing(row)).isEqualTo("true|template_event_unsupported");
        assertThat(syncRequests(account.tenant())).isOne();
        assertThat(output.getAll())
                .contains("webhook.template_event_unsupported provider=whatsapp webhook_event_id=" + row);
    }

    @Test
    void aTemplateNorthstarDoesNotHaveIsNeverCreatedByAWebhookAndASyncIsRequested(CapturedOutput output) {
        Account account = account();
        Template unknown = new Template(null, Long.toString(IDS.incrementAndGet()));
        long before = templates(account.tenant());

        UUID row = deliver(account, statusChange(unknown, "APPROVED", null), T0);
        runJob(account.tenant(), row);

        assertThat(templates(account.tenant()))
                .as("a webhook has no body to store")
                .isEqualTo(before);
        assertThat(processing(row)).isEqualTo("true|template_unknown");
        assertThat(syncRequests(account.tenant())).isOne();
        assertThat(output.getAll())
                .contains("webhook.template_unknown provider=whatsapp webhook_event_id=" + row
                        + " template_id=null error=template_unknown");
    }

    @Test
    void anOlderWebhookProcessedLaterChangesNothing(CapturedOutput output) {
        Account account = account();
        Template template = template(account, "approved");
        UUID newer = deliver(account, statusChange(template, "PAUSED", "NONE"), T0 + 600);
        UUID older = deliver(account, statusChange(template, "REINSTATED", null), T0);

        runJob(account.tenant(), newer);
        runJob(account.tenant(), older);

        assertThat(status(template)).isEqualTo("paused");
        assertThat(metaUpdatedAt(template)).isEqualTo(T0 + 600);
        assertThat(processing(older)).isEqualTo("true|");
        assertThat(output.getAll())
                .contains("webhook.template_stale provider=whatsapp webhook_event_id=" + older + " template_id="
                        + template.id());
    }

    @Test
    void approvedAtIsSetAtTheFirstApprovalFromMetasTimeAndKept() {
        Account account = account();
        Template template = template(account, "submitted");

        runJob(account.tenant(), deliver(account, statusChange(template, "APPROVED", null), T0));
        runJob(account.tenant(), deliver(account, statusChange(template, "PAUSED", null), T0 + 60));
        runJob(account.tenant(), deliver(account, statusChange(template, "APPROVED", null), T0 + 120));

        assertThat(status(template)).isEqualTo("approved");
        assertThat(epochOf(template, "approved_at")).isEqualTo(T0);
        assertThat(metaUpdatedAt(template)).isEqualTo(T0 + 120);
    }

    // Categories

    @Test
    void aCategoryChangeMetaAppliedIsStoredAndANoticeIsOnlyLogged(CapturedOutput output) {
        Account account = account();
        Template template = template(account, "approved");

        UUID notice = deliver(
                account,
                categoryValue(template)
                        .put("correct_category", "MARKETING")
                        .put("new_category", "UTILITY")
                        .put("category_update_timestamp", T0 + 86_400),
                T0);
        runJob(account.tenant(), notice);
        assertThat(category(template)).isEqualTo("UTILITY");
        assertThat(output.getAll())
                .contains("whatsapp.template.category_change_notice template_id=" + template.id()
                        + " current=UTILITY upcoming=MARKETING");

        UUID applied = deliver(
                account,
                categoryValue(template).put("previous_category", "UTILITY").put("new_category", "MARKETING"),
                T0 + 86_400);
        runJob(account.tenant(), applied);

        assertThat(category(template)).isEqualTo("MARKETING");
        assertThat(status(template)).isEqualTo("approved");
        assertThat(processing(applied)).isEqualTo("true|");
        assertThat(output.getAll())
                .contains("whatsapp.template.category_changed template_id=" + template.id()
                        + " from=UTILITY to=MARKETING source=webhook");
    }

    @Test
    void aCategoryOfAnUndocumentedShapeIsNotStored() {
        Account account = account();
        Template template = template(account, "approved");

        UUID row = deliver(
                account,
                categoryValue(template).put("previous_category", "UTILITY").put("new_category", "marketing!"),
                T0);
        runJob(account.tenant(), row);

        assertThat(category(template)).isEqualTo("UTILITY");
        assertThat(processing(row)).isEqualTo("true|template_category_unrecognised");
    }

    // P1, P9, P10, P16

    @Test
    void P1_aRepeatedStatusWebhookIsStoredOnceAndProcessingItAgainChangesNothing(CapturedOutput output) {
        Account account = account();
        Template template = template(account, "approved");
        String body = WhatsAppPayloads.delivery()
                .accountChange(
                        account.waba(),
                        T0,
                        WhatsAppTemplateUpdate.STATUS_FIELD,
                        statusChange(template, "PAUSED", "NONE"))
                .json();
        for (WebhookDelivery delivery : script.twice(body, Duration.ofMinutes(1))) {
            assertThat(post(delivery)).hasStatusOk();
        }
        List<UUID> rows = rows(account.waba(), WhatsAppTemplateUpdate.STATUS_FIELD);
        assertThat(rows).hasSize(1);

        runJob(account.tenant(), rows.getFirst());
        runJob(account.tenant(), rows.getFirst());

        assertThat(status(template)).isEqualTo("paused");
        assertThat(output.getAll())
                .contains("webhook.already_processed provider=whatsapp webhook_event_id=" + rows.getFirst());
    }

    @Test
    void P9_aWebhookRoutedToOneTenantNamingAnotherTenantsTemplateChangesNothingThere() {
        Account a = account();
        Account b = account();
        Template ofB = template(b, "approved");

        // Routed to A by A's WABA, naming B's template ID: A has no such template.
        UUID row = deliver(a, statusChange(ofB, "DISABLED", null), T0);
        runJob(a.tenant(), row);

        assertThat(status(ofB)).isEqualTo("approved");
        assertThat(processing(row)).isEqualTo("true|template_unknown");
    }

    @Test
    void P10_aJobWithoutATenantProcessesNothing() {
        Account account = account();
        Template template = template(account, "approved");
        UUID row = deliver(account, statusChange(template, "PAUSED", null), T0);

        assertThatThrownBy(() -> runJob(null, row)).isInstanceOf(JobTenantException.class);

        assertThat(processing(row)).isEqualTo("false|");
        assertThat(status(template)).isEqualTo("approved");
    }

    @Test
    void P16_aTemplateWebhookForAnUnknownAccountIsParked() {
        String unknown = WhatsAppPayloads.wabaId();
        Template template = new Template(null, Long.toString(IDS.incrementAndGet()));

        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .accountChange(
                                unknown,
                                T0,
                                WhatsAppTemplateUpdate.STATUS_FIELD,
                                statusChange(template, "APPROVED", null))
                        .json())))
                .hasStatusOk();

        Map<String, Object> parked = admin.sql("""
                        select reason, routing_key, provider_event_id from ingest.unrouted_webhooks
                        where routing_key = :k""").param("k", unknown).query().singleRow();
        assertThat(parked).containsEntry("reason", "unknown_account").containsEntry("routing_key", unknown);
        assertThat((String) parked.get("provider_event_id"))
                .startsWith("message_template_status_update:" + unknown + ":" + T0 + ":");
    }

    @Test
    void logsCarryIdsStatusesAndCodesOnly(CapturedOutput output) {
        Account account = account();
        Template template = template(account, "approved");

        UUID row = deliver(account, statusChange(template, "REJECTED", "INCORRECT_CATEGORY"), T0);
        runJob(account.tenant(), row);

        assertThat(output.getAll())
                .contains("whatsapp.template.status_changed template_id=" + template.id()
                        + " from=approved to=rejected source=webhook reason=incorrect_category")
                .doesNotContain(template.providerId())
                .doesNotContain("booking_secret_name")
                .doesNotContain(account.waba());
        assertThat(output.getAll()
                        .lines()
                        .filter(line -> line.contains("whatsapp.template.status_changed template_id=" + template.id()))
                        .findFirst()
                        .orElseThrow())
                .as("a template that can no longer send is the admin's warning until Phase 8")
                .contains("WARN");
    }

    // Helpers

    private record Account(UUID tenant, String waba) {}

    /** A template of the account: Northstar's ID (null: not stored) and Meta's. */
    private record Template(UUID id, String providerId) {}

    private Account account() {
        UUID tenant = data.tenant();
        String waba = WhatsAppPayloads.wabaId();
        data.whatsAppNumber(tenant, waba);
        return new Account(tenant, waba);
    }

    private Template template(Account account, String status) {
        String providerId = Long.toString(IDS.incrementAndGet());
        UUID id = admin.sql("""
                        insert into northstar.message_templates
                          (tenant_id, name, language, category, body, provider_template_id, status)
                        values (:t, :name, 'en_US', 'UTILITY', 'Hi {{1}}', :provider, :status) returning id""")
                .param("t", account.tenant())
                .param("name", "booking_secret_name_" + providerId)
                .param("provider", providerId)
                .param("status", status)
                .query(UUID.class)
                .single();
        return new Template(id, providerId);
    }

    /** A status change's value, shaped as Meta's reference shows it, with the template ID as an integer. */
    private static ObjectNode statusChange(Template template, String event, String reason) {
        ObjectNode value = WhatsAppPayloads.object()
                .put("event", event)
                .put("message_template_id", Long.parseLong(template.providerId()))
                .put("message_template_name", "booking_secret_name")
                .put("message_template_language", "en_US")
                .put("message_template_category", "UTILITY");
        if (reason == null) value.putNull("reason");
        else value.put("reason", reason);
        return value;
    }

    private static ObjectNode categoryValue(Template template) {
        return WhatsAppPayloads.object()
                .put("message_template_id", Long.parseLong(template.providerId()))
                .put("message_template_name", "booking_secret_name")
                .put("message_template_language", "en_US");
    }

    /** Delivers one change of the field its value implies at this Meta time; returns its stored row. */
    private UUID deliver(Account account, ObjectNode value, long time) {
        String field = value.has("event") ? WhatsAppTemplateUpdate.STATUS_FIELD : WhatsAppTemplateUpdate.CATEGORY_FIELD;
        List<UUID> before = rows(account.waba(), field);
        assertThat(post(script.deliver(WhatsAppPayloads.delivery()
                        .accountChange(account.waba(), time, field, value)
                        .json())))
                .hasStatusOk();
        List<UUID> after = rows(account.waba(), field);
        after.removeAll(before);
        assertThat(after).hasSize(1);
        return after.getFirst();
    }

    private MvcTestResult post(WebhookDelivery delivery) {
        var request =
                mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(delivery.body());
        delivery.headers().forEach((name, value) -> request.header(name, value));
        return request.exchange();
    }

    private List<UUID> rows(String waba, String field) {
        return new java.util.ArrayList<>(admin.sql("""
                        select id from northstar.webhook_events
                        where provider = 'whatsapp' and provider_event_id like :prefix order by received_at, id""")
                .param("prefix", field + ":" + waba + ":%")
                .query(UUID.class)
                .list());
    }

    /** processed|error */
    private String processing(UUID row) {
        return admin.sql("""
                        select (processed_at is not null) || '|' || coalesce(error, '')
                        from northstar.webhook_events where id = :id""").param("id", row).query(String.class).single();
    }

    private String status(Template template) {
        return column(template, "status");
    }

    private String category(Template template) {
        return column(template, "category");
    }

    private String column(Template template, String column) {
        return admin.sql("select " + column + " from northstar.message_templates where id = :id")
                .param("id", template.id())
                .query(String.class)
                .single();
    }

    private long metaUpdatedAt(Template template) {
        return epochOf(template, "meta_updated_at");
    }

    private long epochOf(Template template, String column) {
        return admin.sql("select extract(epoch from " + column
                        + ")::bigint from northstar.message_templates where id = :id")
                .param("id", template.id())
                .query(Long.class)
                .single();
    }

    private long templates(UUID tenant) {
        return admin.sql("select count(*) from northstar.message_templates where tenant_id = :t")
                .param("t", tenant)
                .query(Long.class)
                .single();
    }

    private long syncRequests(UUID tenant) {
        return admin.sql("""
                        select count(*) from northstar.actions
                        where tenant_id = :t and type = 'whatsapp.sync' and idempotency_key like 'whatsapp-sync:requested:%'""").param("t", tenant).query(Long.class).single();
    }

    /** Runs the job as the worker does, off the mock request Spring's test support binds to the test thread. */
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
