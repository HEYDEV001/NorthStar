package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.ConflictException;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineMoves;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.LeadMoves;
import com.northstar.workflow.LeadOrigin;
import com.northstar.workflow.MoveReason;
import com.northstar.workflow.StaleLeadException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage moves at the same time as other writers (step 3.3, P7, W2): two members, the engine and a member, and a Junk
 * lead restored while its contact's message opens a lead. Five rounds each; every round ends with one consistent
 * history, nothing silently overwritten, one open lead per contact, and no deadlock (each side's lock order: a move
 * locks only its lead; the inbound job locks the contact, then writes the lead).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadMoveConcurrencyTests {

    private static final int ROUNDS = 5;

    private final LeadMoves moves;
    private final EngineMoves engine;
    private final LeadIntake intake;
    private final JdbcClient appJdbc;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    LeadMoveConcurrencyTests(
            @Autowired LeadMoves moves,
            @Autowired EngineMoves engine,
            @Autowired LeadIntake intake,
            @Autowired JdbcClient appJdbc,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.moves = moves;
        this.engine = engine;
        this.intake = intake;
        this.appJdbc = appJdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    private record Shop(UUID tenant, TestData.Member owner, UUID staffUser, UUID staff, UUID version) {}

    private Shop shop(String config) {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staff = data.membership(owner.tenantId(), staffUser, "staff", "active");
        return new Shop(
                owner.tenantId(),
                owner,
                staffUser,
                staff,
                data.workflow(owner.tenantId(), config).versionId());
    }

    @Test
    void P7_twoMembersMovingTheSameLeadAtOnceOneWinsAndTheOtherIsStale() throws Exception {
        Shop shop = shop(StageMoveConfigs.revision1());
        for (int round = 0; round < ROUNDS; round++) {
            UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "qualifying", null);
            CountDownLatch start = new CountDownLatch(1);

            Future<String> byStaff = threads.submit(() -> outcome(
                    start,
                    () -> asStaff(shop, () -> moves.move(lead, "needs_human", 0, MoveReason.CLIENT_REQUEST, false))
                            .kind()));
            Future<String> byAdmin = threads.submit(() -> outcome(
                    start,
                    () -> asAdmin(shop, () -> moves.move(lead, "lost", 0, MoveReason.NOT_INTERESTED, false))
                            .kind()));
            start.countDown();
            List<String> outcomes = List.of(byStaff.get(30, TimeUnit.SECONDS), byAdmin.get(30, TimeUnit.SECONDS));

            assertThat(outcomes).as("round " + round).containsAnyOf("needs_human", "lost");
            assertThat(outcomes).as("round " + round).contains("stale_lead");
            String winner = outcomes.contains("lost") ? "lost" : "needs_human";
            assertThat(stageAndVersion(lead)).as("round " + round).isEqualTo(winner + "|1");
            assertThat(count("timeline_events", lead)).as("one change, one row").isOne();
            assertThat(count("events", lead))
                    .as("one change, one staff.action event")
                    .isOne();
        }
    }

    @Test
    void P7_anEngineMoveAndAMembersMoveAtOnceLeaveOneConsistentHistory() throws Exception {
        Shop shop = shop(StageMoveConfigs.revision1());
        for (int round = 0; round < ROUNDS; round++) {
            UUID lead = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "qualifying", null);
            CountDownLatch start = new CountDownLatch(1);

            Future<String> byStaff = threads.submit(() -> outcome(
                    start,
                    () -> asStaff(shop, () -> moves.move(lead, "needs_human", 0, MoveReason.CLIENT_REQUEST, false))
                            .kind()));
            Future<String> byEngine = threads.submit(() -> outcome(
                    start,
                    () -> asSystem(
                            shop,
                            () -> engine.toLost(
                                            lead,
                                            new EngineMoves.Trigger("timer.fired", null, EngineMoves.Actor.SYSTEM),
                                            "client_unresponsive")
                                    .code())));
            start.countDown();
            String staff = byStaff.get(30, TimeUnit.SECONDS);
            String system = byEngine.get(30, TimeUnit.SECONDS);

            // The engine always gets the lead to Lost: first, or after the member's move, read again.
            assertThat(system).as("round " + round).isEqualTo("moved");
            assertThat(staff).as("round " + round).isIn("needs_human", "stale_lead");
            int changes = staff.equals("needs_human") ? 2 : 1;
            assertThat(stageAndVersion(lead)).as("round " + round).isEqualTo("lost|" + changes);
            assertThat(count("timeline_events", lead)).isEqualTo(changes);
        }
    }

    @Test
    void W2_restoringAJunkLeadWhileItsContactsMessageOpensALeadLeavesOneOpenLeadAndNoDeadlock() throws Exception {
        Shop shop = shop(StageMoveConfigs.FULL);
        for (int round = 0; round < ROUNDS; round++) {
            UUID junk = data.leadInStage(shop.tenant(), shop.version(), shop.staff(), "junk", null);
            UUID contact = admin.sql("select contact_id from northstar.leads where id = :id")
                    .param("id", junk)
                    .query(UUID.class)
                    .single();
            UUID conversation = data.conversation(shop.tenant(), contact, shop.staff());
            UUID message = data.message(shop.tenant(), conversation);
            CountDownLatch start = new CountDownLatch(1);

            Future<String> restore = threads.submit(() -> outcome(
                    start,
                    () -> asStaff(shop, () -> moves.move(junk, "qualifying", 0, MoveReason.RESTORED, false))
                            .kind()));
            Future<String> inbound = threads.submit(() -> outcome(
                    start,
                    () -> asSystem(shop, () -> {
                        // As the inbound job does (step 2.4b): the contact's lock first, then its open lead or a new
                        // one.
                        appJdbc.sql("select id from northstar.contacts where tenant_id = ? and id = ? for update")
                                .param(shop.tenant())
                                .param(contact)
                                .query(UUID.class)
                                .single();
                        LeadIntake.OpenLead lead = intake.findOpenLead(contact)
                                .map(open -> intake.linkConversation(open, conversation))
                                .orElseGet(() -> intake.open(new LeadIntake.NewLead(
                                        contact,
                                        conversation,
                                        shop.version(),
                                        LeadOrigin.DIRECT,
                                        shop.staff(),
                                        message)));
                        return lead.created() ? "opened" : "joined";
                    })));
            start.countDown();
            String restored = restore.get(30, TimeUnit.SECONDS);
            String opened = inbound.get(30, TimeUnit.SECONDS);

            List<UUID> open =
                    admin.sql("""
                            select id from northstar.leads
                            where contact_id = :c and closed_at is null and deleted_at is null""").param("c", contact).query(UUID.class).list();
            assertThat(open)
                    .as("round " + round + ": one open lead per contact")
                    .hasSize(1);
            if (restored.equals("restore")) {
                assertThat(opened)
                        .as("round " + round + ": the message joined the restored lead")
                        .isEqualTo("joined");
                assertThat(open).containsExactly(junk);
            } else {
                assertThat(restored).as("round " + round).isEqualTo("contact_has_open_lead");
                assertThat(opened).isEqualTo("opened");
                assertThat(open).doesNotContain(junk);
            }
        }
    }

    // Helpers

    /** Waits for the start, runs the work, and returns its result or the refusal's code. */
    private static String outcome(CountDownLatch start, Supplier<String> work) throws InterruptedException {
        start.await();
        try {
            return work.get();
        } catch (StaleLeadException stale) {
            return stale.code();
        } catch (ConflictException conflict) {
            return conflict.code();
        }
    }

    private <T> T asAdmin(Shop shop, Supplier<T> work) {
        return TenantScope.call(
                new TenantContext(shop.tenant(), shop.owner().userId(), Role.ADMIN),
                () -> transactions.execute(status -> work.get()));
    }

    private <T> T asStaff(Shop shop, Supplier<T> work) {
        return TenantScope.call(
                new TenantContext(shop.tenant(), shop.staffUser(), Role.STAFF),
                () -> transactions.execute(status -> work.get()));
    }

    private <T> T asSystem(Shop shop, Supplier<T> work) {
        return SystemScope.call(shop.tenant(), () -> transactions.execute(status -> work.get()));
    }

    private String stageAndVersion(UUID lead) {
        return admin.sql("select stage || '|' || version from northstar.leads where id = :id")
                .param("id", lead)
                .query(String.class)
                .single();
    }

    private long count(String table, UUID lead) {
        return admin.sql("select count(*) from northstar." + table + " where lead_id = :lead")
                .param("lead", lead)
                .query(Long.class)
                .single();
    }
}
