package com.northstar.crm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The leads API for the state machine (step 3.3b, workflow-spec.md section 3) through the real login path, against
 * Postgres with RLS on: reading a lead with its stage names, moving it with an Idempotency-Key (P21) and the version it
 * was decided on (P7), every refusal with its code, the order of the checks, W2 through the API, the timeline's pages,
 * and who reads and moves which lead (P9, P11).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LeadApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST_ID = "lead-api-test";

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    LeadApiTests(@Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    /** A business on one published config, with an admin and two staff members. */
    private record Team(
            UUID tenant,
            UUID adminUser,
            UUID adminMembership,
            UUID staffUser,
            UUID staff,
            UUID colleagueUser,
            UUID colleague,
            UUID version) {}

    private Team team(String config) {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staff = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID colleagueUser = data.user();
        UUID colleague = data.membership(owner.tenantId(), colleagueUser, "staff", "active");
        return new Team(
                owner.tenantId(),
                owner.userId(),
                owner.membershipId(),
                staffUser,
                staff,
                colleagueUser,
                colleague,
                data.workflow(owner.tenantId(), config).versionId());
    }

    private Team team() {
        return team(workflowTemplate());
    }

    // Reading a lead

    @Test
    void aLeadIsReadWithItsStageNamesAndEveryFieldPresent() {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);

        JsonNode read = json(get(team.staffUser(), "/api/v1/leads/" + lead), 200);

        assertThat(read.get("id").stringValue()).isEqualTo(lead.toString());
        assertThat(read.get("stage").stringValue()).isEqualTo("new");
        assertThat(read.get("stageName").stringValue()).isEqualTo("New");
        for (String empty : List.of("previousStage", "previousStageName", "closedAt", "conversationId")) {
            assertThat(read.has(empty)).as(empty + " is present").isTrue();
            assertThat(read.get(empty).isNull()).as(empty).isTrue();
        }
        assertThat(read.get("version").intValue()).isZero();
        assertThat(read.get("stageEnteredAt").stringValue()).endsWith("Z");
        assertThat(read.get("origin").stringValue()).isEqualTo("direct");
        assertThat(read.get("workflowVersionId").stringValue())
                .isEqualTo(team.version().toString());
        assertThat(read.get("assignedMembershipId").stringValue())
                .isEqualTo(team.staff().toString());
        assertThat(read.get("contact").get("id").stringValue())
                .isEqualTo(contactOf(lead).toString());
        assertThat(json(get(team.adminUser(), "/api/v1/leads/" + lead), 200)).isEqualTo(read);

        // In Needs Human: the stage it came from, and a reserved stage's default name.
        UUID escalated = data.leadInStage(team.tenant(), team.version(), team.staff(), "needs_human", "qualifying");
        JsonNode needsHuman = json(get(team.staffUser(), "/api/v1/leads/" + escalated), 200);
        assertThat(needsHuman.get("stageName").stringValue()).isEqualTo("Needs Human");
        assertThat(needsHuman.get("previousStage").stringValue()).isEqualTo("qualifying");
        assertThat(needsHuman.get("previousStageName").stringValue()).isEqualTo("Qualifying");

        // A closed lead has its closing time; a version the engine cannot run has no names.
        UUID lost = data.leadInStage(team.tenant(), team.version(), team.staff(), "lost", null);
        assertThat(json(get(team.staffUser(), "/api/v1/leads/" + lost), 200)
                        .get("closedAt")
                        .stringValue())
                .endsWith("Z");
        Team old = team(SCHEMA_VERSION_2);
        UUID unsupported = data.leadInStage(old.tenant(), old.version(), old.staff(), "new", null);
        JsonNode unnamed = json(get(old.staffUser(), "/api/v1/leads/" + unsupported), 200);
        assertThat(unnamed.get("stage").stringValue()).isEqualTo("new");
        assertThat(unnamed.get("stageName").isNull()).isTrue();
    }

    @Test
    void manualEntryAnswersWithTheSameLead() {
        Team team = team();

        JsonNode entered = json(enter(team.staffUser(), phone(), key()), 201);

        assertThat(entered.get("stageName").stringValue()).isEqualTo("New");
        assertThat(entered.get("version").intValue()).isZero();
        assertThat(json(
                        get(
                                team.staffUser(),
                                "/api/v1/leads/" + entered.get("id").stringValue()),
                        200))
                .isEqualTo(entered);
    }

    // Moving a lead

    @Test
    void aMoveAnswersWithTheLeadAfterItAndItsKeyNamesTheMovesTimelineRow(CapturedOutput output) {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        String key = key();

        MvcTestResult result = move(team.staffUser(), lead, move("qualifying", 0, "client_request"), key);

        JsonNode moved = json(result, 200);
        assertThat(moved.get("stage").stringValue()).isEqualTo("qualifying");
        assertThat(moved.get("stageName").stringValue()).isEqualTo("Qualifying");
        assertThat(moved.get("version").intValue()).isOne();
        assertThat(moved.get("contact").get("id").stringValue())
                .isEqualTo(contactOf(lead).toString());
        assertThat(json(get(team.staffUser(), "/api/v1/leads/" + lead), 200)).isEqualTo(moved);
        UUID row = admin.sql("select id from northstar.timeline_events where lead_id = :lead")
                .param("lead", lead)
                .query(UUID.class)
                .single();
        assertThat(admin.sql("""
                        select operation || '|' || membership_id || '|' || target_id from northstar.request_keys
                        where tenant_id = :t and idempotency_key = :k""")
                        .param("t", team.tenant())
                        .param("k", key)
                        .query(String.class)
                        .single())
                .isEqualTo("lead.move_stage|" + team.staff() + "|" + row);
        assertThat(output.getAll()).doesNotContain(key).doesNotContain(phoneOf(lead));
    }

    @Test
    void P21_aRepeatedMoveAnswersWithTheLeadAsItIsNowAndMovesNothingAgain(CapturedOutput output) throws Exception {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        String key = key();
        String body = move("qualifying", 0, "client_request");

        assertThat(json(move(team.staffUser(), lead, body, key), 200)
                        .get("version")
                        .intValue())
                .isOne();
        JsonNode again = json(move(team.staffUser(), lead, body, key), 200);
        assertThat(again.get("stage").stringValue()).isEqualTo("qualifying");
        assertThat(again.get("version").intValue()).as("not moved again").isOne();
        assertThat(rowCount(lead)).isOne();
        assertThat(output.getAll()).contains("lead.move_repeated lead_id=" + lead);

        // Moved on since: a late repeat answers with the lead as it is now, and still moves nothing.
        json(move(team.staffUser(), lead, move("needs_human", 1, "client_request"), key()), 200);
        JsonNode late = json(move(team.staffUser(), lead, body, key), 200);
        assertThat(late.get("stage").stringValue()).isEqualTo("needs_human");
        assertThat(late.get("version").intValue()).isEqualTo(2);
        assertThat(rowCount(lead)).isEqualTo(2);

        // The same key with another request is 422; keys belong to their member.
        MvcTestResult reused = move(team.staffUser(), lead, move("lost", 2, "client_request"), key);
        assertThat(reused).hasStatus(422);
        assertThat(code(reused)).isEqualTo("idempotency_key_reused");
        assertThat(json(move(team.adminUser(), lead, move("lost", 2, "client_request"), key), 200)
                        .get("stage")
                        .stringValue())
                .as("the admin's own key")
                .isEqualTo("lost");

        // Five at once with one key: one move, the others answer with it.
        UUID other = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        String shared = key();
        List<MvcTestResult> results =
                together(5, () -> move(team.staffUser(), other, move("qualifying", 0, "client_request"), shared));
        assertThat(results).allSatisfy(each -> assertThat(each).hasStatus(200));
        assertThat(rowCount(other)).isOne();
        assertThat(stageAndVersion(other)).isEqualTo("qualifying|1");
    }

    @Test
    void P21_aKeyForALeadTheCallerCanNoLongerSeeIsReused() {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        String key = key();
        String body = move("qualifying", 0, "client_request");
        json(move(team.staffUser(), lead, body, key), 200);
        admin.sql("update northstar.leads set assigned_membership_id = :m where id = :id")
                .param("m", team.colleague())
                .param("id", lead)
                .update();

        MvcTestResult repeat = move(team.staffUser(), lead, body, key);

        assertThat(repeat).hasStatus(422);
        assertThat(code(repeat)).isEqualTo("idempotency_key_reused");
    }

    @Test
    void P7_aStaleMoveIs409WithTheLatestValuesAndKeepsNoKey() {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        json(move(team.adminUser(), lead, move("qualifying", 0, "client_request"), key()), 200);
        String key = key();

        MvcTestResult stale = move(team.staffUser(), lead, move("needs_human", 0, "client_request"), key);

        assertThat(stale).hasStatus(409);
        JsonNode error = json(stale);
        assertThat(error.get("code").stringValue()).isEqualTo("stale_lead");
        JsonNode latest = error.get("details").get("lead");
        assertThat(latest.get("id").stringValue()).isEqualTo(lead.toString());
        assertThat(latest.get("stage").stringValue()).isEqualTo("qualifying");
        assertThat(latest.get("version").intValue()).isOne();
        assertThat(latest.get("workflowVersionId").stringValue())
                .isEqualTo(team.version().toString());
        assertThat(latest.get("stageEnteredAt").stringValue()).endsWith("Z");
        assertThat(latest.has("previousStage") && latest.get("previousStage").isNull())
                .isTrue();
        assertThat(latest.has("closedAt") && latest.get("closedAt").isNull()).isTrue();
        assertThat(keyCount(team, key)).as("a refused move keeps no key").isZero();

        // The same key, decided again on the latest values.
        assertThat(json(move(team.staffUser(), lead, move("needs_human", 1, "client_request"), key), 200)
                        .get("stage")
                        .stringValue())
                .isEqualTo("needs_human");
    }

    @Test
    void P7_twoTabsMovingAtOnceOneWinsAndTheOtherGetsTheLatestValues() throws Exception {
        Team team = team();
        for (int round = 0; round < 5; round++) {
            UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "qualifying", null);
            List<MvcTestResult> results =
                    together(2, () -> move(team.staffUser(), lead, move("needs_human", 0, "client_request"), key()));

            List<Integer> statuses = results.stream()
                    .map(each -> each.getResponse().getStatus())
                    .sorted()
                    .toList();
            assertThat(statuses).as("round " + round).containsExactly(200, 409);
            MvcTestResult loser = results.stream()
                    .filter(each -> each.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(code(loser)).isEqualTo("stale_lead");
            assertThat(json(loser).get("details").get("lead").get("version").intValue())
                    .isOne();
            assertThat(rowCount(lead)).isOne();
        }
    }

    @Test
    void everyRefusalAnswersWithItsCodeAndKeepsNoKey() {
        Team team = team(fullConfig());
        Team old = team(SCHEMA_VERSION_2);
        UUID fresh = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        UUID paying = data.leadInStage(team.tenant(), team.version(), team.staff(), "payment_pending", null);
        UUID junk = data.leadInStage(team.tenant(), team.version(), team.staff(), "junk", null);
        admin.sql("""
                        insert into northstar.leads (tenant_id, contact_id, workflow_version_id, stage, origin)
                        select tenant_id, contact_id, workflow_version_id, 'new', 'direct'
                        from northstar.leads where id = :id""").param("id", junk).update();
        UUID unsupported = data.leadInStage(old.tenant(), old.version(), old.staff(), "new", null);

        record Refusal(UUID user, UUID lead, String body, int status, String code) {}
        for (Refusal refusal : List.of(
                new Refusal(team.staffUser(), fresh, move("quoted", 0, "other"), 409, "transition_not_allowed"),
                new Refusal(team.staffUser(), fresh, move("new", 0, "other"), 409, "stage_unchanged"),
                new Refusal(team.staffUser(), fresh, move("contacted", 0, "other"), 422, "stage_unknown"),
                new Refusal(
                        team.staffUser(), paying, move("booked", 0, "handled_offline"), 409, "transition_engine_only"),
                new Refusal(team.staffUser(), junk, move("qualifying", 0, "restored"), 409, "contact_has_open_lead"),
                new Refusal(
                        old.staffUser(),
                        unsupported,
                        move("qualifying", 0, "other"),
                        409,
                        "workflow_version_unsupported"))) {
            String key = key();
            MvcTestResult result = move(refusal.user(), refusal.lead(), refusal.body(), key);
            assertThat(result).as(refusal.code()).hasStatus(refusal.status());
            assertThat(code(result)).isEqualTo(refusal.code());
            assertThat(admin.sql("select count(*) from northstar.request_keys where idempotency_key = :k")
                            .param("k", key)
                            .query(Long.class)
                            .single())
                    .as(refusal.code() + " keeps no key")
                    .isZero();
        }
        assertThat(rowCount(fresh) + rowCount(paying) + rowCount(junk) + rowCount(unsupported))
                .isZero();
    }

    @Test
    void theBodyIsCheckedThenAStaffMembersForceThenTheKeyThenTheLead() {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        UUID missing = UUID.randomUUID();

        for (String bad : List.of(
                "{\"expectedVersion\": 0, \"reason\": \"other\"}",
                "{\"to\": \"Qualifying\", \"expectedVersion\": 0, \"reason\": \"other\"}",
                "{\"to\": \"qualifying\", \"reason\": \"other\"}",
                "{\"to\": \"qualifying\", \"expectedVersion\": -1, \"reason\": \"other\"}",
                "{\"to\": \"qualifying\", \"expectedVersion\": 1.5, \"reason\": \"other\"}",
                "{\"to\": \"qualifying\", \"expectedVersion\": 0}",
                "{\"to\": \"qualifying\", \"expectedVersion\": 0, \"reason\": \"other\", \"note\": \"text\"}",
                "{\"to\": \"qualifying\", \"expectedVersion\": 0, \"reason\": \"other\", \"force\": \"yes\"}")) {
            for (UUID target : List.of(lead, missing)) {
                MvcTestResult result = move(team.staffUser(), target, bad, null);
                assertThat(result).as(bad).hasStatus(400);
            }
        }
        for (UUID target : List.of(lead, missing)) {
            MvcTestResult reason = move(team.staffUser(), target, move("qualifying", 0, "Client asked"), null);
            assertThat(reason).hasStatus(400);
            assertThat(code(reason)).isEqualTo("reason_invalid");

            MvcTestResult forced = move(
                    team.staffUser(),
                    target,
                    "{\"to\": \"qualifying\", \"expectedVersion\": 0, \"reason\": \"other\", \"force\": true}",
                    null);
            assertThat(forced).as("before the key and the lead").hasStatus(403);
            assertThat(code(forced)).isEqualTo("admin_only");

            MvcTestResult noKey = move(team.staffUser(), target, move("qualifying", 0, "other"), null);
            assertThat(noKey).hasStatus(400);
            assertThat(code(noKey)).isEqualTo("idempotency_key_required");
            MvcTestResult badKey = move(team.staffUser(), target, move("qualifying", 0, "other"), "has space");
            assertThat(badKey).hasStatus(400);
            assertThat(code(badKey)).isEqualTo("idempotency_key_invalid");
        }
        MvcTestResult notJson = mvc.post()
                .uri("/api/v1/leads/" + lead + "/stage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(team.staffUser()))
                .header("Idempotency-Key", key())
                .contentType(MediaType.TEXT_PLAIN)
                .content("qualifying")
                .exchange();
        assertThat(notJson).hasStatus(415);
        assertThat(stageAndVersion(lead)).isEqualTo("new|0");
    }

    @Test
    void anAdminsForcedMoveIsAnsweredLikeAnyMoveAndAudited() {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);

        JsonNode forced = json(
                move(
                        team.adminUser(),
                        lead,
                        "{\"to\": \"scored\", \"expectedVersion\": 0, \"reason\": \"data_correction\", \"force\": true}",
                        key()),
                200);

        assertThat(forced.get("stage").stringValue()).isEqualTo("scored");
        assertThat(admin.sql("""
                        select action || '|' || target_id from northstar.audit_log
                        where tenant_id = :t and action = 'lead.stage_forced'""").param("t", team.tenant()).query(String.class).single())
                .isEqualTo("lead.stage_forced|" + lead);
    }

    @Test
    void W2_aLeadClosedThroughTheApiFreesItsContactCancelledToo() {
        Team team = team(fullConfig());
        UUID lost = data.leadInStage(team.tenant(), team.version(), team.staff(), "quoted", null);
        UUID cancelled = data.leadInStage(team.tenant(), team.version(), team.staff(), "booked", null);

        json(move(team.staffUser(), lost, move("lost", 0, "chose_competitor"), key()), 200);
        json(
                move(
                        team.adminUser(),
                        cancelled,
                        "{\"to\": \"cancelled\", \"expectedVersion\": 0, \"reason\": \"client_request\","
                                + " \"force\": true}",
                        key()),
                200);

        for (UUID closed : List.of(lost, cancelled)) {
            JsonNode next = json(enter(team.staffUser(), phoneOf(closed), key()), 201);
            assertThat(next.get("contact").get("id").stringValue())
                    .as("a new lead for the same contact")
                    .isEqualTo(contactOf(closed).toString());
            assertThat(next.get("id").stringValue()).isNotEqualTo(closed.toString());
        }
    }

    // The timeline

    @Test
    void theTimelinePagesNewestFirstWithTheVersionsStageNames() {
        Team team = team();
        UUID lead = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        json(move(team.staffUser(), lead, move("qualifying", 0, "client_request"), key()), 200);
        json(move(team.staffUser(), lead, move("needs_human", 1, "client_request"), key()), 200);
        json(move(team.staffUser(), lead, move("qualifying", 2, "handed_back"), key()), 200);

        JsonNode first = json(get(team.staffUser(), "/api/v1/leads/" + lead + "/timeline?limit=2"), 200);

        assertThat(first.get("items")).hasSize(2);
        JsonNode newest = first.get("items").get(0);
        assertThat(newest.get("type").stringValue()).isEqualTo("stage_changed");
        assertThat(newest.get("summary").stringValue()).isEqualTo("hand_back");
        assertThat(newest.get("actorType").stringValue()).isEqualTo("staff");
        assertThat(newest.get("actorMembershipId").stringValue())
                .isEqualTo(team.staff().toString());
        assertThat(newest.get("refTable").stringValue()).isEqualTo("events");
        assertThat(newest.get("refId").stringValue()).matches("[0-9a-f-]{36}");
        assertThat(newest.get("occurredAt").stringValue()).endsWith("Z");
        JsonNode stored = newest.get("data");
        assertThat(stored.isObject()).as("the stored object, not text").isTrue();
        assertThat(stored.get("from").stringValue()).isEqualTo("needs_human");
        assertThat(stored.get("to").stringValue()).isEqualTo("qualifying");
        assertThat(stored.get("reason").stringValue()).isEqualTo("handed_back");
        assertThat(stored.get("lead_version").intValue()).isEqualTo(3);
        assertThat(first.get("items").get(1).get("summary").stringValue()).isEqualTo("needs_human");
        assertThat(JSON.writeValueAsString(first.get("stageNames")))
                .as("every stage of the lead's version, in order")
                .isEqualTo("{\"new\":\"New\",\"qualifying\":\"Qualifying\",\"scored\":\"Scored\","
                        + "\"needs_human\":\"Needs Human\",\"lost\":\"Lost\"}");

        String cursor = first.get("nextCursor").stringValue();
        JsonNode second =
                json(get(team.staffUser(), "/api/v1/leads/" + lead + "/timeline?limit=2&cursor=" + cursor), 200);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("summary").stringValue()).isEqualTo("exit");
        assertThat(second.get("nextCursor").isNull()).isTrue();

        // A cursor pages only its own lead; nothing else is a cursor; the limit is 1 to 100.
        UUID other = data.leadInStage(team.tenant(), team.version(), team.staff(), "new", null);
        for (String uri : List.of(
                "/api/v1/leads/" + other + "/timeline?cursor=" + cursor,
                "/api/v1/leads/" + lead + "/timeline?cursor=bm90LWEtY3Vyc29y",
                "/api/v1/leads/" + lead + "/timeline?cursor=%25%25")) {
            MvcTestResult refused = get(team.staffUser(), uri);
            assertThat(refused).as(uri).hasStatus(400);
            assertThat(code(refused)).isEqualTo("invalid_cursor");
        }
        for (String limit : List.of("0", "101", "two")) {
            assertThat(get(team.staffUser(), "/api/v1/leads/" + lead + "/timeline?limit=" + limit))
                    .as(limit)
                    .hasStatus(400);
        }
        JsonNode empty = json(get(team.staffUser(), "/api/v1/leads/" + other + "/timeline"), 200);
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("nextCursor").isNull()).isTrue();
    }

    // Who reads and moves which lead

    @Test
    void P11_staffReadMoveAndSeeTheTimelineOfTheirOwnLeadsOnly(CapturedOutput output) {
        Team team = team();
        UUID theirs = data.leadInStage(team.tenant(), team.version(), team.colleague(), "new", null);
        UUID unassigned = data.leadInStage(team.tenant(), team.version(), null, "new", null);
        UUID missing = UUID.randomUUID();
        List<String> missingAnswers = answers(team.staffUser(), missing);

        for (UUID lead : List.of(theirs, unassigned)) {
            assertThat(answers(team.staffUser(), lead))
                    .as("the same 404 as a missing lead, for every endpoint")
                    .isEqualTo(missingAnswers);
            assertThat(stageAndVersion(lead)).isEqualTo("new|0");
            assertThat(output.getAll()).contains("lead.move_refused lead_id=" + lead + " reason=not_found");
        }
        assertThat(missingAnswers).allSatisfy(answer -> assertThat(answer).startsWith("404|"));
        // The admin reads and moves every lead of the business, the colleague their own.
        assertThat(get(team.adminUser(), "/api/v1/leads/" + unassigned)).hasStatus(200);
        assertThat(get(team.colleagueUser(), "/api/v1/leads/" + theirs + "/timeline"))
                .hasStatus(200);
        json(move(team.colleagueUser(), theirs, move("qualifying", 0, "other"), key()), 200);
    }

    @Test
    void P9_anotherBusinesssLeadIsTheSame404AsAMissingOne() {
        Team team = team();
        Team other = team();
        UUID foreign = data.leadInStage(other.tenant(), other.version(), other.staff(), "new", null);

        assertThat(answers(team.adminUser(), foreign)).isEqualTo(answers(team.adminUser(), UUID.randomUUID()));
        assertThat(stageAndVersion(foreign)).isEqualTo("new|0");
        assertThat(rowCount(foreign)).isZero();
    }

    // Helpers

    /** status|body of GET lead, POST stage and GET timeline, the request ID fixed so bodies compare. */
    private List<String> answers(UUID user, UUID lead) {
        List<String> answers = new ArrayList<>();
        for (MvcTestResult result : List.of(
                get(user, "/api/v1/leads/" + lead),
                move(user, lead, move("qualifying", 0, "other"), key()),
                get(user, "/api/v1/leads/" + lead + "/timeline"))) {
            answers.add(result.getResponse().getStatus() + "|" + body(result));
        }
        return answers;
    }

    private static String move(String to, int expectedVersion, String reason) {
        return "{\"to\": \"" + to + "\", \"expectedVersion\": " + expectedVersion + ", \"reason\": \"" + reason + "\"}";
    }

    private MvcTestResult move(UUID user, UUID lead, String body, String idempotencyKey) {
        var post = mvc.post()
                .uri("/api/v1/leads/" + lead + "/stage")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, REQUEST_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) post = post.header("Idempotency-Key", idempotencyKey);
        return post.exchange();
    }

    private MvcTestResult enter(UUID user, String phone, String idempotencyKey) {
        return mvc.post()
                .uri("/api/v1/leads")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, REQUEST_ID)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\": \"" + phone + "\"}")
                .exchange();
    }

    private MvcTestResult get(UUID user, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, REQUEST_ID)
                .exchange();
    }

    /** Runs the requests on their own threads, all released at once, and returns their results. */
    private List<MvcTestResult> together(int count, Callable<MvcTestResult> request) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcTestResult>> submitted = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            submitted.add(threads.submit(() -> {
                ready.countDown();
                go.await();
                return request.call();
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<MvcTestResult> results = new ArrayList<>();
        for (Future<MvcTestResult> result : submitted) results.add(result.get(60, TimeUnit.SECONDS));
        return results;
    }

    private static String key() {
        return "lead-api-" + UUID.randomUUID();
    }

    /** A random 10-digit Indian mobile, as a member types it. */
    private static String phone() {
        return "+91" + (9_000_000_000L + (long) (Math.random() * 999_999_999L));
    }

    private UUID contactOf(UUID lead) {
        return admin.sql("select contact_id from northstar.leads where id = :id")
                .param("id", lead)
                .query(UUID.class)
                .single();
    }

    private String phoneOf(UUID lead) {
        return admin.sql("""
                        select c.normalized_phone from northstar.contacts c
                        join northstar.leads l on l.contact_id = c.id where l.id = :id""").param("id", lead).query(String.class).single();
    }

    private String stageAndVersion(UUID lead) {
        return admin.sql("select stage || '|' || version from northstar.leads where id = :id")
                .param("id", lead)
                .query(String.class)
                .single();
    }

    private long rowCount(UUID lead) {
        return admin.sql("select count(*) from northstar.timeline_events where lead_id = :lead")
                .param("lead", lead)
                .query(Long.class)
                .single();
    }

    private long keyCount(Team team, String key) {
        return admin.sql("select count(*) from northstar.request_keys where tenant_id = :t and idempotency_key = :k")
                .param("t", team.tenant())
                .param("k", key)
                .query(Long.class)
                .single();
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
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String code(MvcTestResult result) {
        return json(result).get("code").stringValue();
    }

    // The configs: the default template's revision 1, a fuller one, and one the engine cannot run.

    private static String workflowTemplate() {
        try (var in = LeadApiTests.class.getResourceAsStream("/workflow-templates/shoot_production/1.json")) {
            if (in == null) throw new IllegalStateException("The template's revision 1 is on the classpath");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String fullConfig() {
        return """
                {"schema_version": 1, "template": "shoot_production",
                 "stages": [
                   {"key": "new", "name": "New", "exits": [{"to": "qualifying", "on": "message.received"}]},
                   {"key": "qualifying", "name": "Qualifying"},
                   {"key": "quoted", "name": "Quoted", "exits": [{"to": "payment_pending", "on": "button.clicked"}]},
                   {"key": "payment_pending", "name": "Payment pending",
                    "exits": [{"to": "booked", "on": "payment.captured"}]},
                   {"key": "booked", "name": "Booked"},
                   {"key": "cancelled"}, {"key": "junk"}, {"key": "lost"}, {"key": "needs_human"}
                 ]}""";
    }

    private static final String SCHEMA_VERSION_2 = """
            {"schema_version": 2, "stages": [{"key": "new", "name": "New"}, {"key": "qualifying", "name": "Qualifying"},
              {"key": "needs_human"}, {"key": "lost"}]}""";
}
