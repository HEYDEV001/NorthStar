package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workflow editor's API (step 3.1b; workflow-spec.md section 16) through the real login path, against Postgres with
 * RLS on: every answer, drafts read as sent, the Idempotency-Key on the three writes (P21), staff refused first, and
 * another business's workflow a 404 whatever the body holds (P9). The P9 suite ({@code EndpointIsolationTests}) covers
 * these endpoints too, with no body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class WorkflowEndpointsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST_ID = "workflow-api-test";

    private static final String MINIMAL =
            "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\"}, {\"key\": \"needs_human\"}]}";
    private static final String WITH_LOST =
            "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\"},"
                    + " {\"key\": \"needs_human\"}, {\"key\": \"lost\"}]}";
    private static final String INCOMPLETE =
            "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\","
                    + " \"exits\": [{\"to\": \"quoting\", \"on\": \"staff.action\"}]}]}";

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final JdbcClient admin;

    WorkflowEndpointsTests(
            @Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** A business with an admin, a second admin and a staff member. */
    private record Business(UUID tenant, TestData.Member owner, UUID secondAdmin, UUID staffUser) {}

    private Business business() {
        TestData.Member owner = data.member("admin");
        UUID second = data.user();
        data.membership(owner.tenantId(), second, "admin", "active");
        UUID staff = data.user();
        data.membership(owner.tenantId(), staff, "staff", "active");
        return new Business(owner.tenantId(), owner, second, staff);
    }

    @Test
    void anAdminCreatesEditsPublishesAndReadsTheWorkflow(CapturedOutput output) {
        Business shop = business();
        String createKey = key();

        MvcTestResult created = create(shop.owner().userId(), "Studio flow", "blank", createKey);

        assertThat(created).hasStatus(201);
        JsonNode workflow = json(created);
        UUID id = UUID.fromString(workflow.get("id").stringValue());
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/workflows/" + id);
        assertThat(workflow.get("name").stringValue()).isEqualTo("Studio flow");
        assertThat(workflow.get("template").stringValue()).isEqualTo("blank");
        assertThat(workflow.get("currentVersionId").isNull()).isTrue();
        assertThat(workflow.get("draft").isNull()).isTrue();
        assertThat(workflow.get("createdAt").stringValue()).endsWith("Z");

        JsonNode list =
                json(call(HttpMethod.GET, "/api/v1/workflows", shop.owner().userId(), null, null), 200);
        assertThat(list.get("items").size()).isOne();
        assertThat(list.get("items").get(0).get("id").stringValue()).isEqualTo(id.toString());
        assertThat(list.get("items").get(0).has("draft"))
                .as("a list carries no drafts")
                .isFalse();

        JsonNode saved = json(saveDraft(shop.owner().userId(), id, MINIMAL, key()), 200);
        String draftHash = saved.get("draftHash").stringValue();
        assertThat(draftHash).matches("[0-9a-f]{64}");
        assertThat(saved.get("warnings").isEmpty()).isTrue();
        assertThat(json(validate(shop.owner().userId(), id, MINIMAL), 200)
                        .get("problems")
                        .isEmpty())
                .isTrue();
        JsonNode read = json(
                call(HttpMethod.GET, "/api/v1/workflows/" + id, shop.owner().userId(), null, null), 200);
        assertThat(read.get("draft")).as("the draft as JSON").isEqualTo(JSON.readTree(MINIMAL));
        assertThat(read.get("draftHash").stringValue()).isEqualTo(draftHash);

        MvcTestResult published = publish(shop.owner().userId(), id, draftHash, key());

        assertThat(published).hasStatus(201);
        assertThat(published.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/v1/workflows/" + id + "/versions/1");
        JsonNode version = json(published);
        assertThat(version.get("version").intValue()).isOne();
        assertThat(version.get("workflowId").stringValue()).isEqualTo(id.toString());
        assertThat(version.get("publishedBy").stringValue())
                .isEqualTo(shop.owner().membershipId().toString());
        assertThat(version.get("config")).isEqualTo(JSON.readTree(TestData.minimalConfig(id, 1)));
        assertThat(json(
                        call(
                                HttpMethod.GET,
                                "/api/v1/workflows/" + id + "/versions/1",
                                shop.owner().userId(),
                                null,
                                null),
                        200))
                .isEqualTo(version);
        JsonNode after = json(
                call(HttpMethod.GET, "/api/v1/workflows/" + id, shop.owner().userId(), null, null), 200);
        assertThat(after.get("currentVersion").intValue()).isOne();
        assertThat(after.get("currentVersionId").stringValue())
                .isEqualTo(version.get("id").stringValue());

        assertThat(output.getOut())
                .contains("workflow.created workflow_id=" + id, "workflow.published workflow_id=" + id)
                .doesNotContain("Studio flow", createKey, draftHash, "needs_human");
    }

    @Test
    void problemsAreWarningsOnADraftAndA422WithEveryOneOnPublish() {
        Business shop = business();
        UUID id = workflow(shop);

        JsonNode saved = json(saveDraft(shop.owner().userId(), id, INCOMPLETE, key()), 200);

        assertThat(problems(saved.get("warnings")))
                .containsExactly("$.stages stage_needs_human_missing", "$.stages[0].exits[0].to stage_unknown");
        MvcTestResult refused =
                publish(shop.owner().userId(), id, saved.get("draftHash").stringValue(), key());
        JsonNode error = json(refused, 422);
        assertThat(error.get("code").stringValue()).isEqualTo("workflow_config_invalid");
        assertThat(error.get("message").stringValue()).isEqualTo("The workflow config has problems");
        assertThat(problems(error.get("details").get("problems")))
                .containsExactlyInAnyOrder(
                        "$.stages stage_needs_human_missing", "$.stages[0].exits[0].to stage_unknown");
        assertThat(error.get("details").get("requestId").stringValue()).isEqualTo(REQUEST_ID);
        assertThat(versions(id)).isZero();
    }

    @Test
    void aDraftIsTheBodyAsSent() {
        Business shop = business();
        UUID id = workflow(shop);
        UUID owner = shop.owner().userId();
        String stored =
                json(saveDraft(owner, id, MINIMAL, key()), 200).get("draftHash").stringValue();

        assertRefused(
                saveDraft(
                        owner, id, MINIMAL.replace("\"name\": \"New\"", "\"name\": \"New\", \"name\": \"Two\""), key()),
                "$.stages[0].name json_member_duplicate");
        assertRefused(saveDraft(owner, id, "[1]", key()), "$ config_not_object");
        assertRefused(saveDraft(owner, id, "", key()), "$ json_invalid");
        assertRefused(
                call(HttpMethod.PUT, "/api/v1/workflows/" + id + "/draft", owner, key(), bytes("{\"a\": \"ÿ\"}", true)),
                "$ json_invalid");
        String large = "{\"pad\": \"" + "a".repeat(ConfigJson.MAX_BYTES) + "\"}";
        assertRefused(saveDraft(owner, id, large, key()), "$ config_too_large");
        assertRefused(saveDraft(owner, UUID.randomUUID(), large, key()), "$ config_too_large");
        assertThat(json(call(HttpMethod.GET, "/api/v1/workflows/" + id, owner, null, null), 200)
                        .get("draftHash")
                        .stringValue())
                .as("each refused draft left the stored one as it was")
                .isEqualTo(stored);

        String fraction = MINIMAL.replace(
                "{\"key\": \"new\", \"name\": \"New\"}",
                "{\"key\": \"new\", \"name\": \"New\", \"step\": \"qualify\", \"params\": {\"max_turns\": 4.0}}");
        assertThat(problems(json(saveDraft(owner, id, fraction, key()), 200).get("warnings")))
                .containsExactly("$.stages[0].params.max_turns type_invalid");
        assertThat(body(call(HttpMethod.GET, "/api/v1/workflows/" + id, owner, null, null)))
                .as("a fraction comes back as it was sent")
                .contains("\"max_turns\": 4.0");

        MvcTestResult text = mvc.put()
                .uri("/api/v1/workflows/" + id + "/draft")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(owner))
                .header("Idempotency-Key", key())
                .contentType(MediaType.TEXT_PLAIN)
                .content(MINIMAL)
                .exchange();
        assertThat(text).hasStatus(415);
    }

    @Test
    void validatingStoresNothingAndAlwaysAnswers200() {
        Business shop = business();
        UUID id = workflow(shop);
        UUID owner = shop.owner().userId();

        assertThat(problems(json(validate(owner, id, "not json"), 200).get("problems")))
                .containsExactly("$ json_invalid");
        assertThat(problems(json(validate(owner, id, INCOMPLETE), 200).get("problems")))
                .containsExactly("$.stages stage_needs_human_missing", "$.stages[0].exits[0].to stage_unknown");
        assertThat(json(call(HttpMethod.GET, "/api/v1/workflows/" + id, owner, null, null), 200)
                        .get("draft")
                        .isNull())
                .isTrue();
    }

    @Test
    void P21_aRepeatedCreateAnswersWithTheSameWorkflow() {
        Business shop = business();
        String key = key();

        MvcTestResult first = create(shop.owner().userId(), "Studio flow", "blank", key);
        MvcTestResult again = create(shop.owner().userId(), "  Studio flow ", "blank", key);

        assertThat(first).hasStatus(201);
        assertThat(again).as("the same request, as normalised").hasStatus(200);
        assertThat(json(again).get("id")).isEqualTo(json(first).get("id"));
        assertThat(workflows(shop.tenant())).isOne();
        assertCode(create(shop.owner().userId(), "Other", "blank", key), 422, "idempotency_key_reused");
        assertCode(create(shop.owner().userId(), "Other", "blank", null), 400, "idempotency_key_required");
        assertCode(create(shop.owner().userId(), "Other", "blank", "has space"), 400, "idempotency_key_invalid");
        assertCode(
                create(shop.secondAdmin(), "Studio flow", "blank", key),
                409,
                "workflow_exists"); // another member's key is their own: this is a new request
    }

    @Test
    void P21_aLateDraftRetryNeverOverwritesANewerDraft() {
        Business shop = business();
        UUID id = workflow(shop);
        UUID owner = shop.owner().userId();
        String first = key();
        JsonNode answered = json(saveDraft(owner, id, INCOMPLETE, first), 200);
        String newer =
                json(saveDraft(owner, id, MINIMAL, key()), 200).get("draftHash").stringValue();

        MvcTestResult retried = saveDraft(owner, id, INCOMPLETE, first);

        assertThat(json(retried, 200)).as("the first answer, again").isEqualTo(answered);
        assertThat(json(call(HttpMethod.GET, "/api/v1/workflows/" + id, owner, null, null), 200)
                        .get("draftHash")
                        .stringValue())
                .as("the newer draft stays")
                .isEqualTo(newer);
        assertCode(saveDraft(owner, id, WITH_LOST, first), 422, "idempotency_key_reused");
    }

    @Test
    void P21_aRepeatedPublishMakesNoSecondVersionAndAFailedOneKeepsNoKey() {
        Business shop = business();
        UUID id = workflow(shop);
        UUID owner = shop.owner().userId();
        String hash =
                json(saveDraft(owner, id, MINIMAL, key()), 200).get("draftHash").stringValue();
        String key = key();

        assertCode(publish(owner, id, "0".repeat(64), key), 409, "draft_changed");
        MvcTestResult first = publish(owner, id, hash, key);
        MvcTestResult again = publish(owner, id, hash, key);

        assertThat(first).as("the refused attempt kept no key").hasStatus(201);
        assertThat(again).hasStatus(200);
        assertThat(json(again)).isEqualTo(json(first));
        assertThat(versions(id)).isOne();
        String next = json(saveDraft(owner, id, WITH_LOST, key()), 200)
                .get("draftHash")
                .stringValue();
        assertCode(publish(owner, id, next, key), 422, "idempotency_key_reused");
        assertThat(json(publish(owner, id, next, key()), 201).get("version").intValue())
                .isEqualTo(2);
    }

    @Test
    void conflictsComeBackAs409() {
        Business shop = business();
        UUID id = workflow(shop);
        UUID owner = shop.owner().userId();

        assertCode(create(owner, "Another", "agency", key()), 409, "workflow_exists");
        assertCode(publish(owner, id, "0".repeat(64), key()), 409, "draft_missing");
        String hash =
                json(saveDraft(owner, id, MINIMAL, key()), 200).get("draftHash").stringValue();
        data.workflowVersion(shop.tenant()); // a second workflow made outside the service, published
        assertCode(publish(owner, id, hash, key()), 409, "another_workflow_published");
    }

    @Test
    void badRequestsAre400sAndUnknownOnesAre422sOr404s() {
        Business shop = business();
        UUID id = workflow(shop);
        UUID owner = shop.owner().userId();

        assertCode(create(owner, "   ", "blank", key()), 422, "workflow_name_invalid");
        assertCode(create(owner, "Fine", "bakery", key()), 422, "template_unknown");
        assertThat(call(HttpMethod.POST, "/api/v1/workflows", owner, key(), bytes("{\"name\": \"Fine\"}", false)))
                .hasStatus(400);
        assertCode(publish(owner, id, "not a hash", key()), 400, "validation_failed");
        assertThat(call(
                        HttpMethod.POST,
                        "/api/v1/workflows/" + id + "/publish",
                        owner,
                        key(),
                        bytes("{\"draftHash\": \"" + "0".repeat(64) + "\", \"force\": true}", false)))
                .as("the strict mapper refuses unknown fields")
                .hasStatus(400);
        assertCode(
                call(HttpMethod.GET, "/api/v1/workflows/" + id + "/versions/7", owner, null, null), 404, "not_found");
        assertThat(call(HttpMethod.GET, "/api/v1/workflows/" + id + "/versions/one", owner, null, null))
                .hasStatus(400);
    }

    @Test
    void staffAreRefusedOnEveryEndpointBeforeTheKeyOrTheConfig() {
        Business shop = business();
        UUID id = workflow(shop);
        UUID staff = shop.staffUser();
        String large = "{\"pad\": \"" + "a".repeat(ConfigJson.MAX_BYTES) + "\"}";
        String hash = "0".repeat(64);

        List<MvcTestResult> attempts = new ArrayList<>(List.of(
                call(HttpMethod.GET, "/api/v1/workflows", staff, null, null),
                create(staff, "Mine", "blank", null),
                call(HttpMethod.GET, "/api/v1/workflows/" + id, staff, null, null),
                saveDraft(staff, id, MINIMAL, null),
                saveDraft(staff, id, large, key()),
                validate(staff, id, large),
                publish(staff, id, hash, null),
                call(HttpMethod.GET, "/api/v1/workflows/" + id + "/versions/1", staff, null, null)));
        for (MvcTestResult attempt : attempts) assertCode(attempt, 403, "admin_only");

        assertThat(admin.sql("""
                        select count(*) from northstar.request_keys k
                        join northstar.memberships m on m.tenant_id = k.tenant_id and m.id = k.membership_id
                        where m.user_id = :u""").param("u", staff).query(Long.class).single())
                .as("no key was claimed")
                .isZero();
        assertThat(admin.sql("select draft_config is null from northstar.workflows where id = :w")
                        .param("w", id)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void withoutATokenNothingIsAnswered() {
        assertThat(mvc.get().uri("/api/v1/workflows").exchange()).hasStatus(401);
    }

    @Test
    void P9_anotherBusinessesWorkflowIsA404WhateverTheBodyHolds() {
        Business mine = business();
        Business theirs = business();
        UUID id = workflow(theirs);
        String hash = json(saveDraft(theirs.owner().userId(), id, MINIMAL, key()), 200)
                .get("draftHash")
                .stringValue();
        UUID me = mine.owner().userId();

        for (MvcTestResult attempt : List.of(
                saveDraft(me, id, "[1]", key()),
                saveDraft(me, id, MINIMAL.replace("\"name\": \"New\"", "\"name\": \"New\", \"name\": \"Two\""), key()),
                saveDraft(me, id, WITH_LOST, key()),
                validate(me, id, "[]"),
                publish(me, id, hash, key()),
                call(HttpMethod.GET, "/api/v1/workflows/" + id, me, null, null),
                call(HttpMethod.GET, "/api/v1/workflows/" + id + "/versions/1", me, null, null))) {
            assertCode(attempt, 404, "not_found");
        }
        assertThat(json(call(HttpMethod.GET, "/api/v1/workflows", me, null, null), 200)
                        .get("items")
                        .isEmpty())
                .isTrue();
        assertThat(json(
                                call(
                                        HttpMethod.GET,
                                        "/api/v1/workflows/" + id,
                                        theirs.owner().userId(),
                                        null,
                                        null),
                                200)
                        .get("draftHash")
                        .stringValue())
                .isEqualTo(hash);
        assertThat(versions(id)).isZero();
        assertThat(admin.sql("select count(*) from northstar.request_keys where tenant_id = :t")
                        .param("t", mine.tenant())
                        .query(Long.class)
                        .single())
                .as("no key kept for a write that found nothing")
                .isZero();
    }

    // Helpers.

    private UUID workflow(Business shop) {
        JsonNode created = json(create(shop.owner().userId(), "Test workflow", "blank", key()), 201);
        return UUID.fromString(created.get("id").stringValue());
    }

    private MvcTestResult create(UUID user, String name, String template, String key) {
        String body = JSON.createObjectNode()
                .put("name", name)
                .put("template", template)
                .toString();
        return call(HttpMethod.POST, "/api/v1/workflows", user, key, bytes(body, false));
    }

    private MvcTestResult saveDraft(UUID user, UUID workflow, String draft, String key) {
        return call(HttpMethod.PUT, "/api/v1/workflows/" + workflow + "/draft", user, key, bytes(draft, false));
    }

    private MvcTestResult validate(UUID user, UUID workflow, String draft) {
        return call(HttpMethod.POST, "/api/v1/workflows/" + workflow + "/validate", user, null, bytes(draft, false));
    }

    private MvcTestResult publish(UUID user, UUID workflow, String draftHash, String key) {
        String body = JSON.createObjectNode().put("draftHash", draftHash).toString();
        return call(HttpMethod.POST, "/api/v1/workflows/" + workflow + "/publish", user, key, bytes(body, false));
    }

    private MvcTestResult call(HttpMethod method, String uri, UUID user, String key, byte[] body) {
        var request = mvc.method(method)
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, REQUEST_ID);
        if (key != null) request = request.header("Idempotency-Key", key);
        if (body != null)
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return request.exchange();
    }

    /** The text as UTF-8, or with {@code latin1} as ISO-8859-1, which is not UTF-8 for any character above 127. */
    private static byte[] bytes(String text, boolean latin1) {
        return text.getBytes(latin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    private long versions(UUID workflow) {
        return admin.sql("select count(*) from northstar.workflow_versions where workflow_id = :w")
                .param("w", workflow)
                .query(Long.class)
                .single();
    }

    private long workflows(UUID tenant) {
        return admin.sql("select count(*) from northstar.workflows where tenant_id = :t")
                .param("t", tenant)
                .query(Long.class)
                .single();
    }

    private static void assertRefused(MvcTestResult result, String problem) {
        JsonNode error = json(result, 422);
        assertThat(error.get("code").stringValue()).isEqualTo("workflow_config_invalid");
        assertThat(problems(error.get("details").get("problems"))).containsExactly(problem);
    }

    private static void assertCode(MvcTestResult result, int status, String code) {
        assertThat(json(result, status).get("code").stringValue()).isEqualTo(code);
    }

    private static List<String> problems(JsonNode list) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            found.add(list.get(i).get("path").stringValue() + " "
                    + list.get(i).get("code").stringValue());
        }
        return found;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode json(MvcTestResult result, int status) {
        assertThat(result).hasStatus(status);
        return json(result);
    }

    private static JsonNode json(MvcTestResult result) {
        return JSON.readTree(body(result));
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
