package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineMoves;
import com.northstar.workflow.EngineMoves.Actor;
import com.northstar.workflow.EngineMoves.Result;
import com.northstar.workflow.EngineMoves.Trigger;
import com.northstar.workflow.LeadChangedConcurrentlyException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine's stage moves (step 3.3, workflow-spec.md section 3) as the system actor: exits only on their own event,
 * filter rules, Needs Human and back, Lost, re-opening, W36, versions it cannot run, P7's re-read and its bounded
 * retries, and the context it needs (P9, P10).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EngineMovesTests {

    private final EngineMoves engine;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    EngineMovesTests(
            @Autowired EngineMoves engine,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.engine = engine;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    private record Shop(UUID tenant, TestData.Member owner, UUID workflow, UUID version) {}

    private Shop shop(String config) {
        TestData.Member owner = data.member("admin");
        TestData.Published published = data.workflow(owner.tenantId(), config);
        return new Shop(owner.tenantId(), owner, published.workflowId(), published.versionId());
    }

    // Exits

    @Test
    void anExitIsFollowedOnlyOnItsOwnEventWithTheTriggerOnTheTimeline(CapturedOutput output) {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, "new", null);
        UUID message = UUID.randomUUID();
        UUID extracted = UUID.randomUUID();

        assertThat(asSystem(shop, () -> engine.followExit(lead, "qualifying", trigger("staff.action", null))))
                .as("New → Qualifying waits for message.received")
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(shop, () -> engine.followExit(lead, "scored", trigger("message.received", null))))
                .as("not an exit of New")
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(
                        shop,
                        () -> engine.followExit(
                                lead, "qualifying", new Trigger("message.received", message, Actor.CONTACT))))
                .isEqualTo(Result.MOVED);
        assertThat(asSystem(
                        shop,
                        () -> engine.followExit(
                                lead, "scored", new Trigger("ai.fields_extracted", extracted, Actor.AI))))
                .isEqualTo(Result.MOVED);

        assertThat(rows(lead))
                .containsExactly(
                        "exit|contact||events|" + message + "|new>qualifying|message.received||false|1",
                        "exit|ai||events|" + extracted + "|qualifying>scored|ai.fields_extracted||false|2");
        assertThat(output.getAll())
                .contains("lead.move_refused lead_id=" + lead + " reason=no_such_exit")
                .contains("lead.stage_changed lead_id=" + lead
                        + " from=qualifying to=scored move=exit actor=ai lead_version=2");
    }

    @Test
    void theEngineFollowsExitsOnPaymentEventsAndReopensALeadOnlyForAFreeContact() {
        Shop shop = shop(StageMoveConfigs.FULL);
        UUID paying = data.leadInStage(shop.tenant(), shop.version(), null, "payment_pending", null);

        assertThat(asSystem(shop, () -> engine.followExit(paying, "booked", trigger("staff.action", null))))
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(shop, () -> engine.followExit(paying, "booked", trigger("payment.captured", null))))
                .isEqualTo(Result.MOVED);

        // Junk's own exit back to New re-opens the lead, unless its contact has another open lead.
        UUID junk = data.leadInStage(shop.tenant(), shop.version(), null, "junk", null);
        UUID blocked = data.leadInStage(shop.tenant(), shop.version(), null, "junk", null);
        admin.sql("""
                        insert into northstar.leads (tenant_id, contact_id, workflow_version_id, stage, origin)
                        select tenant_id, contact_id, workflow_version_id, 'new', 'direct'
                        from northstar.leads where id = :id""").param("id", blocked).update();

        assertThat(asSystem(shop, () -> engine.followExit(junk, "new", trigger("message.received", null))))
                .isEqualTo(Result.MOVED);
        assertThat(leadState(junk)).isEqualTo("new||open|1||");
        assertThat(asSystem(shop, () -> engine.followExit(blocked, "new", trigger("message.received", null))))
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(leadState(blocked)).isEqualTo("junk||closed|0||");
    }

    // Needs Human, Lost and filter rules

    @Test
    void needsHumanFromEveryOpenStageAndAHandBackToExactlyThatStage() {
        Shop shop = shop(StageMoveConfigs.revision1());
        for (String open : List.of("new", "qualifying", "scored")) {
            UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, open, null);

            assertThat(asSystem(shop, () -> engine.toNeedsHuman(lead, trigger("ai.escalation", null), "ai_escalation")))
                    .as(open)
                    .isEqualTo(Result.MOVED);
            assertThat(leadState(lead)).isEqualTo("needs_human|" + open + "|open|1||");
            assertThat(asSystem(shop, () -> engine.toNeedsHuman(lead, trigger("ai.escalation", null), "ai_escalation")))
                    .as("already there: a no-op")
                    .isEqualTo(Result.UNCHANGED);
            assertThat(asSystem(shop, () -> engine.handBack(lead, trigger("staff.action", null))))
                    .isEqualTo(Result.MOVED);
            assertThat(leadState(lead)).isEqualTo(open + "||open|2||");
            assertThat(asSystem(shop, () -> engine.handBack(lead, trigger("staff.action", null))))
                    .as("not in Needs Human: a no-op")
                    .isEqualTo(Result.UNCHANGED);
            assertThat(rows(lead))
                    .containsExactly(
                            "needs_human|system||||" + open + ">needs_human|ai.escalation|ai_escalation|false|1",
                            "hand_back|system||||needs_human>" + open + "|staff.action||false|2");
        }
        // A lead in Needs Human from a stage its version does not declare (as migration, step 3.11, may leave one).
        UUID stranded = data.leadInStage(shop.tenant(), shop.version(), null, "needs_human", "quoted");
        assertThat(asSystem(shop, () -> engine.handBack(stranded, trigger("staff.action", null))))
                .isEqualTo(Result.NOT_ALLOWED);
        // A closed lead does not go to Needs Human.
        UUID lost = data.leadInStage(shop.tenant(), shop.version(), null, "lost", null);
        assertThat(asSystem(shop, () -> engine.toNeedsHuman(lost, trigger("ai.escalation", null), "ai_escalation")))
                .isEqualTo(Result.NOT_ALLOWED);
    }

    @Test
    void lostFromEveryOpenStageWithItsReason() {
        Shop shop = shop(StageMoveConfigs.FULL);
        for (String open : List.of("new", "qualifying", "scored", "quoted", "booked", "needs_human")) {
            UUID lead = data.leadInStage(
                    shop.tenant(), shop.version(), null, open, open.equals("needs_human") ? "quoted" : null);

            assertThat(asSystem(shop, () -> engine.toLost(lead, trigger("timer.fired", null), "client_unresponsive")))
                    .as(open)
                    .isEqualTo(Result.MOVED);
            assertThat(leadState(lead)).isEqualTo("lost||closed|1|client_unresponsive|");
            assertThat(asSystem(shop, () -> engine.toLost(lead, trigger("timer.fired", null), "client_unresponsive")))
                    .isEqualTo(Result.UNCHANGED);
        }
        for (String closed : List.of("junk", "completed", "cancelled")) {
            UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, closed, null);
            assertThat(asSystem(shop, () -> engine.toLost(lead, trigger("timer.fired", null), "client_unresponsive")))
                    .as(closed)
                    .isEqualTo(Result.NOT_ALLOWED);
        }
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, "new", null);
        assertThatThrownBy(() ->
                        asSystem(shop, () -> engine.toLost(lead, trigger("timer.fired", null), "Client said no")))
                .as("a reason is a fixed code, never text")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> asSystem(shop, () -> engine.toNeedsHuman(lead, trigger("ai.escalation", null), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFilterRuleSendsALeadFromTheScoreStageToJunkWithTheRule() {
        Shop shop = shop(StageMoveConfigs.FULL);
        UUID scored = data.leadInStage(shop.tenant(), shop.version(), null, "scored", null);
        UUID qualifying = data.leadInStage(shop.tenant(), shop.version(), null, "qualifying", null);

        assertThat(asSystem(
                        shop, () -> engine.toJunkByFilterRule(scored, "zone", trigger("ai.fields_extracted", null))))
                .as("not a rule of the version")
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(
                        shop,
                        () -> engine.toJunkByFilterRule(
                                qualifying, "min_budget", trigger("ai.fields_extracted", null))))
                .as("filter rules run from the score stage")
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(
                        shop,
                        () -> engine.toJunkByFilterRule(scored, "min_budget", trigger("ai.fields_extracted", null))))
                .isEqualTo(Result.MOVED);

        assertThat(leadState(scored)).isEqualTo("junk||closed|1||min_budget");
        assertThat(admin.sql("""
                        select summary || '|' || (data->>'rule_id') from northstar.timeline_events
                        where lead_id = :lead""").param("lead", scored).query(String.class).single())
                .isEqualTo("filter_rule|min_budget");
        assertThat(asSystem(
                        shop,
                        () -> engine.toJunkByFilterRule(scored, "min_budget", trigger("ai.fields_extracted", null))))
                .isEqualTo(Result.UNCHANGED);
    }

    // Versions

    @Test
    void W36_theEngineRunsALeadOnItsOwnVersion() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID onV1 = data.leadInStage(shop.tenant(), shop.version(), null, "new", null);
        String v2 = StageMoveConfigs.revision1()
                .replace(
                        "{\"to\": \"qualifying\", \"on\": \"message.received\"}",
                        "{\"to\": \"qualifying\", \"on\": \"button.clicked\"}");
        UUID version2 = data.publish(shop.tenant(), shop.workflow(), 2, v2);
        UUID onV2 = data.leadInStage(shop.tenant(), version2, null, "new", null);

        assertThat(asSystem(shop, () -> engine.followExit(onV1, "qualifying", trigger("button.clicked", null))))
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(shop, () -> engine.followExit(onV2, "qualifying", trigger("message.received", null))))
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(shop, () -> engine.followExit(onV1, "qualifying", trigger("message.received", null))))
                .isEqualTo(Result.MOVED);
        assertThat(asSystem(shop, () -> engine.followExit(onV2, "qualifying", trigger("button.clicked", null))))
                .isEqualTo(Result.MOVED);
    }

    @Test
    void aVersionTheEngineCannotRunLeavesTheLeadWhereItIsAndIsLoggedOnce(CapturedOutput output) {
        Shop shop = shop(StageMoveConfigs.SCHEMA_VERSION_2);
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, "new", null);

        assertThat(asSystem(shop, () -> engine.followExit(lead, "qualifying", trigger("message.received", null))))
                .isEqualTo(Result.VERSION_UNSUPPORTED);
        assertThat(asSystem(shop, () -> engine.toNeedsHuman(lead, trigger("ai.escalation", null), "ai_escalation")))
                .isEqualTo(Result.VERSION_UNSUPPORTED);

        assertThat(leadState(lead)).isEqualTo("new||open|0||");
        assertThat(rows(lead)).isEmpty();
        assertThat(output.getAll())
                .containsOnlyOnce("workflow.version_unsupported workflow_version_id=" + shop.version());
    }

    @Test
    void aLeadWhoseStageItsVersionDoesNotDeclareIsNotMoved() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, "quoted", null);

        assertThat(asSystem(shop, () -> engine.toNeedsHuman(lead, trigger("ai.escalation", null), "ai_escalation")))
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(asSystem(shop, () -> engine.toLost(lead, trigger("timer.fired", null), "client_unresponsive")))
                .isEqualTo(Result.NOT_ALLOWED);
        assertThat(leadState(lead)).isEqualTo("quoted||open|0||");
    }

    // P7

    @Test
    void P7_aChangeBetweenTheReadAndTheUpdateIsReadAgainAndTheMoveCheckedAgain(CapturedOutput output) throws Exception {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID bumped = data.leadInStage(shop.tenant(), shop.version(), null, "qualifying", null);
        UUID moved = data.leadInStage(shop.tenant(), shop.version(), null, "qualifying", null);

        // Another writer changes only the version (as the inbound job's conversation link does): the move still
        // applies.
        Result afterBump = whileAnotherWriterHolds(
                bumped,
                "update northstar.leads set version = version + 1 where id = ?",
                () -> asSystem(
                        shop, () -> engine.toNeedsHuman(bumped, trigger("ai.escalation", null), "ai_escalation")));
        assertThat(afterBump).isEqualTo(Result.MOVED);
        assertThat(leadState(bumped)).isEqualTo("needs_human|qualifying|open|2||");
        assertThat(output.getAll()).contains("lead.move_retried lead_id=" + bumped + " attempt=1");

        // Another writer moves the lead where the engine was taking it: checked again, nothing left to do.
        Result afterMove = whileAnotherWriterHolds(
                moved,
                "update northstar.leads set stage = 'needs_human', previous_stage = 'qualifying', version = version + 1"
                        + " where id = ?",
                () -> asSystem(
                        shop, () -> engine.toNeedsHuman(moved, trigger("ai.escalation", null), "ai_escalation")));
        assertThat(afterMove).isEqualTo(Result.UNCHANGED);
        assertThat(rows(moved))
                .as("the other writer's move is not overwritten or repeated")
                .isEmpty();
    }

    @Test
    void P7_theEngineGivesUpRetryablyAfterThreeAttempts(CapturedOutput output) {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, "qualifying", null);
        // A test-only trigger, created as postgres, that makes every update of this lead miss, as if another writer
        // changed it each time.
        admin.sql("""
                        create function public.test_engine_moves_miss() returns trigger language plpgsql as $$
                        begin
                          if old.id = '%s' then return null; end if;
                          return new;
                        end $$""".formatted(lead)).update();
        admin.sql("""
                        create trigger test_engine_moves_miss before update on northstar.leads
                          for each row execute function public.test_engine_moves_miss()""").update();
        try {
            assertThatThrownBy(() -> asSystem(
                            shop, () -> engine.toLost(lead, trigger("timer.fired", null), "client_unresponsive")))
                    .isInstanceOfSatisfying(
                            LeadChangedConcurrentlyException.class,
                            failure -> assertThat(failure.leadId()).isEqualTo(lead));
        } finally {
            admin.sql("drop trigger test_engine_moves_miss on northstar.leads").update();
            admin.sql("drop function public.test_engine_moves_miss()").update();
        }
        assertThat(output.getAll())
                .contains("lead.move_retried lead_id=" + lead + " attempt=1")
                .contains("lead.move_retried lead_id=" + lead + " attempt=3")
                .contains("lead.move_gave_up lead_id=" + lead + " attempts=3")
                .doesNotContain("lead.move_retried lead_id=" + lead + " attempt=4");
        assertThat(leadState(lead)).isEqualTo("qualifying||open|0||");
    }

    // Context

    @Test
    void notFoundForAMissingOrDeletedLeadOrOneOfAnotherBusiness() {
        Shop shop = shop(StageMoveConfigs.revision1());
        Shop other = shop(StageMoveConfigs.revision1());
        UUID deleted = data.leadInStage(shop.tenant(), shop.version(), null, "new", null);
        admin.sql("update northstar.leads set deleted_at = now() where id = :id")
                .param("id", deleted)
                .update();
        UUID foreign = data.leadInStage(other.tenant(), other.version(), null, "new", null);

        for (UUID lead : List.of(UUID.randomUUID(), deleted, foreign)) {
            assertThat(asSystem(shop, () -> engine.toNeedsHuman(lead, trigger("ai.escalation", null), "ai_escalation")))
                    .isEqualTo(Result.NOT_FOUND);
        }
        assertThat(leadState(foreign)).isEqualTo("new||open|0||");
    }

    @Test
    void P9_theEngineMovesOnlyItsOwnTenantsLeads() {
        Shop shop = shop(StageMoveConfigs.revision1());
        Shop other = shop(StageMoveConfigs.revision1());
        UUID foreign = data.leadInStage(other.tenant(), other.version(), null, "new", null);

        assertThat(asSystem(shop, () -> engine.followExit(foreign, "qualifying", trigger("message.received", null))))
                .isEqualTo(Result.NOT_FOUND);
        assertThat(asSystem(shop, () -> engine.toLost(foreign, trigger("timer.fired", null), "client_unresponsive")))
                .isEqualTo(Result.NOT_FOUND);
        assertThat(leadState(foreign)).isEqualTo("new||open|0||");
        assertThat(rows(foreign)).isEmpty();
    }

    @Test
    void P10_theEngineNeedsTheSystemActorsTenantContext() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), null, "new", null);

        assertThatThrownBy(() -> transactions.execute(
                        status -> engine.followExit(lead, "qualifying", trigger("message.received", null))))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(shop.tenant(), shop.owner().userId(), Role.ADMIN),
                        () -> transactions.execute(
                                status -> engine.followExit(lead, "qualifying", trigger("message.received", null)))))
                .as("members move leads through LeadMoves")
                .isInstanceOf(IllegalStateException.class);
        assertThat(leadState(lead)).isEqualTo("new||open|0||");
    }

    // Helpers

    private static Trigger trigger(String eventType, UUID eventId) {
        return new Trigger(eventType, eventId, Actor.SYSTEM);
    }

    private <T> T asSystem(Shop shop, Supplier<T> work) {
        return SystemScope.call(shop.tenant(), () -> transactions.execute(status -> work.get()));
    }

    /**
     * Runs the move on another thread while a plain connection, as postgres, holds the lead's row with an uncommitted
     * change: the move reads the lead as it was, its update waits for the row, and once the change commits the update
     * finds the lead changed.
     */
    private Result whileAnotherWriterHolds(UUID lead, String change, Supplier<Result> move) throws Exception {
        try (Connection other = postgres.dataSource().getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement statement = other.prepareStatement(change)) {
                statement.setObject(1, lead);
                assertThat(statement.executeUpdate()).isOne();
            }
            Future<Result> result = threads.submit(move::get);
            awaitUpdateWaitingOnALock();
            other.commit();
            return result.get(30, TimeUnit.SECONDS);
        }
    }

    private void awaitUpdateWaitingOnALock() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            long waiting = admin.sql("""
                            select count(*) from pg_stat_activity
                            where wait_event_type = 'Lock' and query like '%update northstar.leads%'""").query(Long.class).single();
            if (waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("The move's update never waited for the lead's row");
    }

    /** stage|previous_stage|open or closed|version|lost_reason|junk_rule_id */
    private String leadState(UUID lead) {
        return admin.sql("""
                        select stage || '|' || coalesce(previous_stage, '') || '|'
                               || case when closed_at is null then 'open' else 'closed' end || '|' || version || '|'
                               || coalesce(lost_reason, '') || '|' || coalesce(junk_rule_id, '')
                        from northstar.leads where id = :id""").param("id", lead).query(String.class).single();
    }

    /** summary|actor|membership|ref table|ref|from>to|trigger|reason|forced|lead version, oldest first. */
    private List<String> rows(UUID lead) {
        return admin.sql("""
                        select summary || '|' || actor_type || '|' || coalesce(actor_membership_id::text, '') || '|'
                               || coalesce(ref_table, '') || '|' || coalesce(ref_id::text, '') || '|'
                               || coalesce(data->>'from', '') || '>' || (data->>'to') || '|'
                               || coalesce(data->>'trigger', '') || '|' || coalesce(data->>'reason', '') || '|'
                               || (data->>'forced') || '|' || (data->>'lead_version')
                        from northstar.timeline_events where lead_id = :lead
                        order by occurred_at, id""").param("lead", lead).query(String.class).list();
    }
}
