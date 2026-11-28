package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.ApiException;
import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.api.UnprocessableException;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.LeadMoves;
import com.northstar.workflow.LeadMoves.LeadStage;
import com.northstar.workflow.LeadMoves.Moved;
import com.northstar.workflow.LeadOrigin;
import com.northstar.workflow.MoveReason;
import com.northstar.workflow.StaleLeadException;
import com.northstar.workflow.Timeline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
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
 * Members' stage moves and the timeline read (step 3.3, workflow-spec.md section 3; data-model.md sections 6 and 10),
 * through the app's own login with RLS: the moves each version allows, an admin's forced moves, P7, closing and
 * re-opening (W2), Needs Human and its hand back, the engine-only exits, W36, versions the engine cannot run, and who
 * moves and reads which lead (P9, P10, P11).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LeadMovesTests {

    private static final List<String> REVISION_1_STAGES = List.of("new", "qualifying", "scored", "needs_human", "lost");

    private final LeadMoves moves;
    private final Timeline timeline;
    private final LeadIntake intake;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    LeadMovesTests(
            @Autowired LeadMoves moves,
            @Autowired Timeline timeline,
            @Autowired LeadIntake intake,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.moves = moves;
        this.timeline = timeline;
        this.intake = intake;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** A business on one published config, with an admin and two staff members. */
    private record Shop(
            UUID tenant,
            TestData.Member owner,
            UUID staffUser,
            UUID staff,
            UUID colleagueUser,
            UUID colleague,
            UUID workflow,
            UUID version) {}

    private Shop shop(String config) {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staff = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID colleagueUser = data.user();
        UUID colleague = data.membership(owner.tenantId(), colleagueUser, "staff", "active");
        TestData.Published published = data.workflow(owner.tenantId(), config);
        return new Shop(
                owner.tenantId(),
                owner,
                staffUser,
                staff,
                colleagueUser,
                colleague,
                published.workflowId(),
                published.versionId());
    }

    // Moves the version allows

    @Test
    void aStaffMemberMovesTheirLeadAlongAnExitWithItsTimelineRowAndEvent(CapturedOutput output) {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);
        Instant entered = stage(lead).stageEnteredAt();

        Moved moved = asStaff(shop, () -> moves.move(lead, "qualifying", 0, MoveReason.CLIENT_REQUEST, false));

        assertThat(moved.kind()).isEqualTo("exit");
        assertThat(moved.lead().stage()).isEqualTo("qualifying");
        assertThat(moved.lead().version())
                .as("P7: every change moves the version on")
                .isOne();
        assertThat(moved.lead().previousStage()).isNull();
        assertThat(moved.lead().closedAt()).isNull();
        assertThat(moved.lead().stageEnteredAt()).isAfter(entered);
        assertThat(stage(lead)).isEqualTo(moved.lead());

        UUID event = admin.sql("""
                        select id from northstar.events
                        where lead_id = :lead and type = 'staff.action'
                          and dedupe_key = 'staff.action:move_stage:' || :lead || ':1'""").param("lead", lead).query(UUID.class).single();
        assertThat(admin.sql("""
                        select payload->>'action' || '|' || (payload->>'from') || '|' || (payload->>'to') || '|'
                               || (payload->>'move') || '|' || (payload->>'reason') || '|' || (payload->>'forced')
                               || '|' || (payload->>'membership_id') || '|' || (payload->>'lead_version') || '|'
                               || ((payload->>'at')::timestamptz = l.stage_entered_at)::text || '|'
                               || (e.processed_at is null)::text
                        from northstar.events e join northstar.leads l on l.id = e.lead_id
                        where e.id = :event""").param("event", event).query(String.class).single())
                .as("the staff.action event: IDs, stage keys and codes; the engine (3.4) reads it")
                .isEqualTo("move_stage|new|qualifying|exit|client_request|false|" + shop.staff() + "|1|true|true");
        assertThat(rows(lead))
                .containsExactly("exit|staff|" + shop.staff() + "|events|" + event
                        + "|new>qualifying|staff.action|client_request|false|1|" + shop.version());
        assertThat(audit(shop.tenant())).as("only forced moves are audited").isEmpty();
        assertThat(output.getAll())
                .contains("lead.stage_changed lead_id=" + lead + " from=new to=qualifying move=exit actor=staff"
                        + " membership_id=" + shop.staff() + " lead_version=1");
    }

    @Test
    void everyMemberMoveOnTheShootProductionTemplatesRevision1() {
        Shop shop = shop(StageMoveConfigs.revision1());
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String from : REVISION_1_STAGES) {
            for (String to : List.of(
                    "new", "qualifying", "scored", "needs_human", "lost", "junk", "quoted", "cancelled", "completed")) {
                String previous = from.equals("needs_human") ? "qualifying" : null;
                UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), from, previous);
                outcomes.put(
                        from + ">" + to,
                        attempt(() -> asStaff(
                                shop,
                                () -> moves.move(lead, to, 0, MoveReason.OTHER, false)
                                        .kind())));
            }
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String from : REVISION_1_STAGES) {
            for (String to : List.of(
                    "new", "qualifying", "scored", "needs_human", "lost", "junk", "quoted", "cancelled", "completed")) {
                expected.put(from + ">" + to, "transition_not_allowed");
            }
            // Stages revision 1 does not declare, and the lead's own stage.
            for (String undeclared : List.of("junk", "quoted", "cancelled", "completed")) {
                expected.put(from + ">" + undeclared, "stage_unknown");
            }
            expected.put(from + ">" + from, "stage_unchanged");
        }
        expected.put("new>qualifying", "exit");
        expected.put("qualifying>scored", "exit");
        for (String open : List.of("new", "qualifying", "scored")) {
            expected.put(open + ">needs_human", "needs_human");
            expected.put(open + ">lost", "lost");
        }
        expected.put("needs_human>qualifying", "hand_back");
        expected.put("needs_human>lost", "lost");
        assertThat(outcomes).containsExactlyEntriesOf(expected);
    }

    @Test
    void aForcedMoveIsAnAdminsAlwaysAuditedAndStillToADeclaredStage() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);

        assertThat(attempt(() -> asStaff(shop, () -> moves.move(lead, "scored", 0, MoveReason.DATA_CORRECTION, true))))
                .as("staff never force a move")
                .isEqualTo("admin_only");
        assertThat(attempt(() -> asAdmin(shop, () -> moves.move(lead, "quoted", 0, MoveReason.DATA_CORRECTION, true))))
                .as("not even an admin moves a lead to a stage its version does not have")
                .isEqualTo("stage_unknown");
        assertThat(rows(lead)).isEmpty();

        Moved forced = asAdmin(shop, () -> moves.move(lead, "scored", 0, MoveReason.DATA_CORRECTION, true));

        assertThat(forced.kind()).isEqualTo("forced");
        assertThat(forced.lead().stage()).isEqualTo("scored");
        assertThat(rows(lead))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .startsWith("forced|staff|" + shop.owner().membershipId() + "|events|")
                        .endsWith("|new>scored|staff.action|data_correction|true|1|" + shop.version()));
        assertThat(eventPayload(lead, 1, "forced")).isEqualTo("true");
        assertThat(audit(shop.tenant()))
                .containsExactly(LeadMoves.STAGE_FORCED + "|leads|" + lead + "|"
                        + shop.owner().userId() + "|{\"to\": \"scored\", \"from\": \"new\"}");

        // A force that is not needed is the ordinary move, and not audited.
        Moved ordinary = asAdmin(shop, () -> moves.move(lead, "lost", 1, MoveReason.NOT_INTERESTED, true));
        assertThat(ordinary.kind()).isEqualTo("lost");
        assertThat(audit(shop.tenant())).hasSize(1);
        // Out of a closing stage, by force only.
        assertThat(attempt(() -> asAdmin(shop, () -> moves.move(lead, "new", 2, MoveReason.DATA_CORRECTION, false))))
                .isEqualTo("transition_not_allowed");
        Moved reopened = asAdmin(shop, () -> moves.move(lead, "new", 2, MoveReason.DATA_CORRECTION, true));
        assertThat(reopened.kind()).isEqualTo("forced");
        assertThat(reopened.lead().closedAt()).as("re-opened").isNull();
        assertThat(leadState(lead)).isEqualTo("new||open|3||");
    }

    // P7

    @Test
    void P7_aMoveMadeAgainstAnotherVersionIsRefusedWithTheLatestValues() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);
        // Someone else changed the lead first (as the inbound job's conversation link does, step 2.4b).
        admin.sql("update northstar.leads set version = version + 1 where id = :id")
                .param("id", lead)
                .update();

        assertThatThrownBy(() -> asStaff(shop, () -> moves.move(lead, "qualifying", 0, MoveReason.OTHER, false)))
                .isInstanceOfSatisfying(StaleLeadException.class, stale -> {
                    assertThat(stale.status().value()).isEqualTo(409);
                    assertThat(stale.code()).isEqualTo("stale_lead");
                    assertThat(stale.current().version()).isOne();
                    assertThat(stale.current().stage()).isEqualTo("new");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> current =
                            (Map<String, Object>) stale.details().get("lead");
                    assertThat(current)
                            .containsEntry("id", lead)
                            .containsEntry("stage", "new")
                            .containsEntry("version", 1)
                            .containsEntry("previousStage", null)
                            .containsEntry("closedAt", null)
                            .containsKeys("workflowVersionId", "stageEnteredAt");
                });
        assertThat(attempt(() -> asStaff(shop, () -> moves.move(lead, "qualifying", 2, MoveReason.OTHER, false))))
                .as("a version from the future is stale too")
                .isEqualTo("stale_lead");
        assertThat(rows(lead)).as("nothing silently overwritten").isEmpty();
        assertThat(leadState(lead)).isEqualTo("new||open|1||");

        assertThat(asStaff(shop, () -> moves.move(lead, "qualifying", 1, MoveReason.OTHER, false))
                        .lead()
                        .version())
                .isEqualTo(2);
    }

    // Closing and re-opening (W2)

    @Test
    void W2_everyClosingStageClosesTheLeadAndFreesTheContactCancelledToo() {
        Shop shop = shop(StageMoveConfigs.FULL);
        Map<String, Supplier<Moved>> closings = new LinkedHashMap<>();
        UUID toLost = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "quoted", null);
        closings.put("lost", () -> moves.move(toLost, "lost", 0, MoveReason.CHOSE_COMPETITOR, false));
        UUID toCompleted = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "booked", null);
        closings.put("completed", () -> moves.move(toCompleted, "completed", 0, MoveReason.HANDLED_OFFLINE, false));
        UUID toJunk = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "qualifying", null);
        closings.put("junk", () -> moves.move(toJunk, "junk", 0, MoveReason.DUPLICATE_LEAD, true));
        UUID toCancelled = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "booked", null);
        closings.put("cancelled", () -> moves.move(toCancelled, "cancelled", 0, MoveReason.CLIENT_REQUEST, true));

        closings.forEach((closing, move) -> {
            Moved moved = asAdmin(shop, move);
            assertThat(moved.lead().stage()).isEqualTo(closing);
            assertThat(moved.lead().closedAt()).as(closing + " closes the lead").isNotNull();
            UUID contact = contactOf(moved.lead().id());
            UUID conversation = data.conversation(shop.tenant(), contact, null);
            LeadIntake.OpenLead next = SystemScope.call(
                    shop.tenant(),
                    () -> transactions.execute(status -> intake.open(new LeadIntake.NewLead(
                            contact,
                            conversation,
                            shop.version(),
                            LeadOrigin.DIRECT,
                            null,
                            data.message(shop.tenant(), conversation)))));
            assertThat(next.created())
                    .as(closing + ": the contact's next message opens a new lead")
                    .isTrue();
            assertThat(next.id()).isNotEqualTo(moved.lead().id());
        });
        assertThat(attempt(() ->
                        asAdmin(shop, () -> moves.move(toCancelled, "booked", 1, MoveReason.DATA_CORRECTION, true))))
                .as("re-opening a closed lead whose contact has another open lead")
                .isEqualTo("contact_has_open_lead");
    }

    @Test
    void restoringAJunkLeadReopensItUnlessItsContactHasAnotherOpenLead() {
        Shop shop = shop(StageMoveConfigs.FULL);
        UUID junk = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "junk", null);
        admin.sql("update northstar.leads set junk_rule_id = 'min_budget' where id = :id")
                .param("id", junk)
                .update();

        Moved restored = asStaff(shop, () -> moves.move(junk, "qualifying", 0, MoveReason.RESTORED, false));

        assertThat(restored.kind()).isEqualTo("restore");
        assertThat(leadState(junk)).as("open again, the rule cleared").isEqualTo("qualifying||open|1||");

        // The contact has another open lead: refused whether the mover can see it or not.
        UUID again = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "junk", null);
        UUID contact = contactOf(again);
        UUID colleaguesLead = admin.sql("""
                        insert into northstar.leads
                          (tenant_id, contact_id, workflow_version_id, stage, origin, assigned_membership_id)
                        values (:t, :c, :v, 'new', 'direct', :m) returning id""")
                .param("t", shop.tenant())
                .param("c", contact)
                .param("v", shop.version())
                .param("m", shop.colleague())
                .query(UUID.class)
                .single();
        assertThat(attempt(() -> asStaff(shop, () -> moves.move(again, "qualifying", 0, MoveReason.RESTORED, false))))
                .as("the one-open-lead index, for a lead the staff member cannot see")
                .isEqualTo("contact_has_open_lead");
        assertThat(attempt(() -> asAdmin(shop, () -> moves.move(again, "qualifying", 0, MoveReason.RESTORED, false))))
                .as("checked first, for a lead the admin can see")
                .isEqualTo("contact_has_open_lead");
        assertThat(leadState(again)).isEqualTo("junk||closed|0||");
        assertThat(rows(again)).isEmpty();
        assertThat(leadState(colleaguesLead)).isEqualTo("new||open|0||");
    }

    // Needs Human, Lost and the engine-only exits

    @Test
    void needsHumanRemembersWhereItCameFromAndAHandBackReturnsExactlyThere() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "scored", null);

        asStaff(shop, () -> moves.move(lead, "needs_human", 0, MoveReason.CLIENT_REQUEST, false));
        assertThat(leadState(lead)).isEqualTo("needs_human|scored|open|1||");
        assertThat(attempt(() -> asStaff(shop, () -> moves.move(lead, "needs_human", 1, MoveReason.OTHER, false))))
                .as("entering it again changes nothing")
                .isEqualTo("stage_unchanged");
        assertThat(attempt(() -> asStaff(shop, () -> moves.move(lead, "qualifying", 1, MoveReason.HANDED_BACK, false))))
                .as("not where it came from")
                .isEqualTo("transition_not_allowed");

        Moved back = asStaff(shop, () -> moves.move(lead, "scored", 1, MoveReason.HANDED_BACK, false));

        assertThat(back.kind()).isEqualTo("hand_back");
        assertThat(leadState(lead)).isEqualTo("scored||open|2||");
        assertThat(rowMoves(lead)).containsExactly("needs_human:scored>needs_human", "hand_back:needs_human>scored");

        // From Needs Human to Lost clears the stage it came from.
        asStaff(shop, () -> moves.move(lead, "needs_human", 2, MoveReason.CLIENT_REQUEST, false));
        asStaff(shop, () -> moves.move(lead, "lost", 3, MoveReason.CLIENT_UNRESPONSIVE, false));
        assertThat(leadState(lead)).isEqualTo("lost||closed|4|client_unresponsive|");
    }

    @Test
    void lostKeepsItsReasonUntilTheLeadLeavesIt() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "qualifying", null);

        asStaff(shop, () -> moves.move(lead, "lost", 0, MoveReason.PRICE_TOO_HIGH, false));
        assertThat(leadState(lead)).isEqualTo("lost||closed|1|price_too_high|");

        asAdmin(shop, () -> moves.move(lead, "qualifying", 1, MoveReason.DATA_CORRECTION, true));
        assertThat(leadState(lead)).isEqualTo("qualifying||open|2||");
    }

    @Test
    void exitsOnPaymentAndRefundEventsAreTheEnginesOnlyAndAnAdminsForceTheOverride() {
        Shop shop = shop(StageMoveConfigs.FULL);
        UUID paying = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "payment_pending", null);
        UUID booked = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "booked", null);

        for (Supplier<String> move : List.<Supplier<String>>of(
                () -> asStaff(
                        shop,
                        () -> moves.move(paying, "booked", 0, MoveReason.HANDLED_OFFLINE, false)
                                .kind()),
                () -> asAdmin(
                        shop,
                        () -> moves.move(paying, "booked", 0, MoveReason.HANDLED_OFFLINE, false)
                                .kind()),
                () -> asStaff(
                        shop,
                        () -> moves.move(booked, "cancelled", 0, MoveReason.CLIENT_REQUEST, false)
                                .kind()))) {
            assertThat(attempt(move)).isEqualTo("transition_engine_only");
        }
        assertThat(asStaff(
                        shop,
                        () -> moves.move(paying, "quoted", 0, MoveReason.CLIENT_REQUEST, false)
                                .kind()))
                .as("a staff exit beside an engine-only one")
                .isEqualTo("exit");
        assertThat(asAdmin(
                        shop,
                        () -> moves.move(booked, "cancelled", 0, MoveReason.CLIENT_REQUEST, true)
                                .kind()))
                .isEqualTo("forced");
        assertThat(audit(shop.tenant())).hasSize(1);
    }

    // Versions (W36) and configs the engine cannot run

    @Test
    void W36_aLeadKeepsTheRulesOfItsOwnVersionAfterAnotherIsPublished() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID onV1 = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);
        // Version 2 replaces New → Qualifying with New → Contacted.
        String v2 = StageMoveConfigs.revision1()
                .replace(
                        "{\"to\": \"qualifying\", \"on\": \"message.received\"}",
                        "{\"to\": \"contacted\", \"on\": \"staff.action\"}")
                .replace(
                        "{\"key\": \"needs_human\"}",
                        "{\"key\": \"contacted\", \"name\": \"Contacted\"}, {\"key\": \"needs_human\"}");
        assertThat(v2).contains("contacted");
        UUID version2 = data.publish(shop.tenant(), shop.workflow(), 2, v2);
        UUID onV2 = data.leadInStage(shop.tenant(), version2, shop.staff(), "new", null);

        assertThat(attempt(() -> asStaff(shop, () -> moves.move(onV1, "contacted", 0, MoveReason.OTHER, false))))
                .as("version 1 has no Contacted")
                .isEqualTo("stage_unknown");
        assertThat(attempt(() -> asStaff(shop, () -> moves.move(onV2, "qualifying", 0, MoveReason.OTHER, false))))
                .as("version 2 has no exit from New to Qualifying")
                .isEqualTo("transition_not_allowed");
        assertThat(asStaff(
                        shop,
                        () -> moves.move(onV1, "qualifying", 0, MoveReason.OTHER, false)
                                .kind()))
                .isEqualTo("exit");
        assertThat(asStaff(
                        shop,
                        () -> moves.move(onV2, "contacted", 0, MoveReason.OTHER, false)
                                .kind()))
                .isEqualTo("exit");
        assertThat(stage(onV1).workflowVersionId()).isEqualTo(shop.version());
    }

    @Test
    void aVersionTheEngineCannotRunRefusesEveryMoveAndIsLoggedOnce(CapturedOutput output) {
        Shop shop = shop(StageMoveConfigs.SCHEMA_VERSION_2);
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);
        UUID other = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);

        assertThat(attempt(() -> asStaff(shop, () -> moves.move(lead, "qualifying", 0, MoveReason.OTHER, false))))
                .isEqualTo("workflow_version_unsupported");
        assertThat(attempt(() -> asAdmin(shop, () -> moves.move(lead, "qualifying", 0, MoveReason.OTHER, true))))
                .as("a forced move too")
                .isEqualTo("workflow_version_unsupported");
        assertThat(attempt(() -> asAdmin(shop, () -> moves.move(other, "lost", 0, MoveReason.OTHER, false))))
                .isEqualTo("workflow_version_unsupported");

        assertThat(leadState(lead)).as("the lead stays where it is").isEqualTo("new||open|0||");
        assertThat(rows(lead)).isEmpty();
        assertThat(output.getAll())
                .containsOnlyOnce("workflow.version_unsupported workflow_version_id=" + shop.version() + " lead_id="
                        + lead + " reason=schema_version_unknown");
    }

    @Test
    void aLeadWhoseStageItsVersionDoesNotDeclareMovesOnlyByForce() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "quoted", null);

        assertThat(attempt(() -> asStaff(shop, () -> moves.move(lead, "lost", 0, MoveReason.OTHER, false))))
                .isEqualTo("transition_not_allowed");
        assertThat(asAdmin(
                        shop,
                        () -> moves.move(lead, "qualifying", 0, MoveReason.DATA_CORRECTION, true)
                                .kind()))
                .isEqualTo("forced");
    }

    // Who moves which lead

    @Test
    void P11_staffMoveAndReadOnlyTheirOwnLeads(CapturedOutput output) {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID theirs = data.leadInStage(shop.tenant(), shop.version(), shop.colleague(), "new", null);
        UUID unassigned = data.leadInStage(shop.tenant(), shop.version(), null, "new", null);

        for (UUID lead : List.of(theirs, unassigned)) {
            assertThatThrownBy(() -> asStaff(shop, () -> moves.move(lead, "qualifying", 0, MoveReason.OTHER, false)))
                    .isInstanceOf(NotFoundException.class);
            assertThat(asStaff(shop, () -> moves.stage(lead))).isEmpty();
            assertThat(asStaff(shop, () -> timeline.page(lead, null, 10))).isEmpty();
            assertThat(rows(lead)).isEmpty();
            assertThat(output.getAll()).contains("lead.move_refused lead_id=" + lead + " reason=not_found");
        }
        // The admin moves any of the business's leads, the colleague their own.
        assertThat(asAdmin(
                        shop,
                        () -> moves.move(unassigned, "qualifying", 0, MoveReason.OTHER, false)
                                .kind()))
                .isEqualTo("exit");
        assertThat(asColleague(
                        shop,
                        () -> moves.move(theirs, "qualifying", 0, MoveReason.OTHER, false)
                                .kind()))
                .isEqualTo("exit");
    }

    @Test
    void P9_anotherBusinesssLeadIsNotFoundAndItsTimelineIsNone() {
        Shop shop = shop(StageMoveConfigs.revision1());
        Shop other = shop(StageMoveConfigs.revision1());
        UUID foreign = data.leadInStage(other.tenant(), other.version(), other.staff(), "new", null);

        assertThatThrownBy(() -> asAdmin(shop, () -> moves.move(foreign, "qualifying", 0, MoveReason.OTHER, false)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> asAdmin(shop, () -> moves.move(foreign, "quoted", 0, MoveReason.OTHER, true)))
                .as("the same 404 whatever the request")
                .isInstanceOf(NotFoundException.class);
        assertThat(asAdmin(shop, () -> moves.stage(foreign))).isEmpty();
        assertThat(asAdmin(shop, () -> timeline.page(foreign, null, 10))).isEmpty();
        assertThat(leadState(foreign)).isEqualTo("new||open|0||");
    }

    @Test
    void P10_movesAndTimelinesNeedAMembersTenantContext() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);

        assertThatThrownBy(() ->
                        transactions.execute(status -> moves.move(lead, "qualifying", 0, MoveReason.OTHER, false)))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> transactions.execute(status -> timeline.page(lead, null, 10)))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> SystemScope.call(
                        shop.tenant(),
                        () -> transactions.execute(
                                status -> moves.move(lead, "qualifying", 0, MoveReason.OTHER, false))))
                .as("the system moves leads through EngineMoves only")
                .isInstanceOf(IllegalStateException.class);
        assertThat(leadState(lead)).isEqualTo("new||open|0||");
    }

    @Test
    void aDeletedLeadIsNotFound() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);
        admin.sql("update northstar.leads set deleted_at = now() where id = :id")
                .param("id", lead)
                .update();

        assertThatThrownBy(() -> asAdmin(shop, () -> moves.move(lead, "qualifying", 0, MoveReason.OTHER, false)))
                .isInstanceOf(NotFoundException.class);
        assertThat(asAdmin(shop, () -> moves.stage(lead))).isEmpty();
        assertThat(asAdmin(shop, () -> timeline.page(lead, null, 10))).isEmpty();
    }

    // The timeline read

    @Test
    void theTimelineIsNewestFirstAndPagedWithTiesBrokenById() {
        Shop shop = shop(StageMoveConfigs.revision1());
        UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "new", null);
        UUID contact = contactOf(lead);
        // Five rows, three of them at the same time.
        List<UUID> stored = new ArrayList<>();
        for (String at : List.of(
                "2026-11-28T10:00:00Z",
                "2026-11-28T11:00:00Z",
                "2026-11-28T11:00:00Z",
                "2026-11-28T11:00:00Z",
                "2026-11-28T12:00:00.000001Z")) {
            stored.add(admin.sql("""
                            insert into northstar.timeline_events
                              (tenant_id, lead_id, contact_id, type, actor_type, summary, data, occurred_at)
                            values (:t, :lead, :c, 'stage_changed', 'system', 'exit', '{}', cast(:at as timestamptz))
                            returning id""")
                    .param("t", shop.tenant())
                    .param("lead", lead)
                    .param("c", contact)
                    .param("at", at)
                    .query(UUID.class)
                    .single());
        }
        List<UUID> expected =
                admin.sql("""
                        select id from northstar.timeline_events where lead_id = :lead
                        order by occurred_at desc, id desc""").param("lead", lead).query(UUID.class).list();
        assertThat(expected.getFirst()).isEqualTo(stored.getLast());

        List<UUID> read = new ArrayList<>();
        Timeline.Position next = null;
        int pages = 0;
        do {
            Timeline.Position after = next;
            Timeline.Page page =
                    asStaff(shop, () -> timeline.page(lead, after, 2)).orElseThrow();
            page.entries().forEach(entry -> read.add(entry.id()));
            next = page.next();
            pages++;
        } while (next != null);

        assertThat(read).isEqualTo(expected);
        assertThat(pages).isEqualTo(3);
        Timeline.Entry newest = asStaff(shop, () -> timeline.page(lead, null, 1))
                .orElseThrow()
                .entries()
                .getFirst();
        assertThat(newest.occurredAt()).isEqualTo(Instant.parse("2026-11-28T12:00:00.000001Z"));
        assertThat(newest.type()).isEqualTo("stage_changed");
        assertThat(newest.actorType()).isEqualTo("system");
        assertThat(newest.actorMembershipId()).isNull();
        assertThat(newest.refTable()).isNull();
        assertThat(newest.summary()).isEqualTo("exit");
        assertThat(newest.data()).isEqualTo("{}");
        assertThatThrownBy(() -> asStaff(shop, () -> timeline.page(lead, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> asStaff(shop, () -> timeline.page(lead, null, Timeline.MAX_PAGE + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Helpers

    /** The refusal's code, or the result when there was none (a move's kind). */
    private static String attempt(Supplier<?> work) {
        try {
            Object result = work.get();
            return result instanceof Moved moved ? moved.kind() : String.valueOf(result);
        } catch (ApiException refused) {
            assertThat(refused)
                    .isInstanceOfAny(ConflictException.class, UnprocessableException.class, ForbiddenException.class);
            return refused.code();
        }
    }

    private <T> T asAdmin(Shop shop, Supplier<T> work) {
        return as(new TenantContext(shop.tenant(), shop.owner().userId(), Role.ADMIN), work);
    }

    private <T> T asStaff(Shop shop, Supplier<T> work) {
        return as(new TenantContext(shop.tenant(), shop.staffUser(), Role.STAFF), work);
    }

    private <T> T asColleague(Shop shop, Supplier<T> work) {
        return as(new TenantContext(shop.tenant(), shop.colleagueUser(), Role.STAFF), work);
    }

    private <T> T as(TenantContext context, Supplier<T> work) {
        return TenantScope.call(context, () -> transactions.execute(status -> work.get()));
    }

    private LeadStage stage(UUID lead) {
        return admin.sql("""
                        select id, workflow_version_id, stage, previous_stage, stage_entered_at, closed_at, version
                        from northstar.leads where id = :id""")
                .param("id", lead)
                .query((row, n) -> new LeadStage(
                        row.getObject("id", UUID.class),
                        row.getObject("workflow_version_id", UUID.class),
                        row.getString("stage"),
                        row.getString("previous_stage"),
                        row.getObject("stage_entered_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        Optional.ofNullable(row.getObject("closed_at", java.time.OffsetDateTime.class))
                                .map(java.time.OffsetDateTime::toInstant)
                                .orElse(null),
                        row.getInt("version")))
                .single();
    }

    /** stage|previous_stage|open or closed|version|lost_reason|junk_rule_id */
    private String leadState(UUID lead) {
        return admin.sql("""
                        select stage || '|' || coalesce(previous_stage, '') || '|'
                               || case when closed_at is null then 'open' else 'closed' end || '|' || version || '|'
                               || coalesce(lost_reason, '') || '|' || coalesce(junk_rule_id, '')
                        from northstar.leads where id = :id""").param("id", lead).query(String.class).single();
    }

    /**
     * summary|actor|membership|ref table|ref|from>to|trigger|reason|forced|lead version|workflow version, oldest
     * first.
     */
    private List<String> rows(UUID lead) {
        return admin.sql("""
                        select summary || '|' || actor_type || '|' || coalesce(actor_membership_id::text, '') || '|'
                               || coalesce(ref_table, '') || '|' || coalesce(ref_id::text, '') || '|'
                               || coalesce(data->>'from', '') || '>' || (data->>'to') || '|'
                               || coalesce(data->>'trigger', '') || '|' || coalesce(data->>'reason', '') || '|'
                               || (data->>'forced') || '|' || (data->>'lead_version') || '|'
                               || (data->>'workflow_version_id')
                        from northstar.timeline_events where lead_id = :lead
                        order by occurred_at, id""").param("lead", lead).query(String.class).list();
    }

    /** summary:from>to, oldest first. */
    private List<String> rowMoves(UUID lead) {
        return admin.sql("""
                        select summary || ':' || coalesce(data->>'from', '') || '>' || (data->>'to')
                        from northstar.timeline_events where lead_id = :lead
                        order by occurred_at, id""").param("lead", lead).query(String.class).list();
    }

    private String eventPayload(UUID lead, int version, String member) {
        return admin.sql("""
                        select payload->>:member from northstar.events
                        where dedupe_key = 'staff.action:move_stage:' || :lead || ':' || :version""")
                .param("member", member)
                .param("lead", lead)
                .param("version", version)
                .query(String.class)
                .single();
    }

    /** action|target table|target|actor user|metadata, oldest first. */
    private List<String> audit(UUID tenant) {
        return admin.sql("""
                        select action || '|' || target_table || '|' || target_id || '|' || actor_user_id || '|'
                               || coalesce(metadata::text, '')
                        from northstar.audit_log where tenant_id = :t order by created_at, id""").param("t", tenant).query(String.class).list();
    }

    private UUID contactOf(UUID lead) {
        return admin.sql("select contact_id from northstar.leads where id = :id")
                .param("id", lead)
                .query(UUID.class)
                .single();
    }
}
