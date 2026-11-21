package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.FakeProvider;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.Actions;
import com.northstar.workflow.ClosedAction;
import com.northstar.workflow.NewAction;
import com.northstar.workflow.OutboundCancellation;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The closing hook (step 2.6a): {@link com.northstar.workflow.ActionHandler#closed} is called exactly once, inside the
 * transaction that records it, whenever an action closes without succeeding, on every path that can close one: a
 * permanent failure, the last retry, a lease lost on the last attempt, and a cancellation for an opted-out recipient at
 * creation, at claim and by the opt-out. Never for a success, a retry still to come or an auth pause; and a hook that
 * throws rolls the closing back with it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActionClosedHookTests {

    private final Actions actions;
    private final OutboundCancellation cancellation;
    private final ExecuteActionHandler executeAction;
    private final FakeProvider provider;
    private final ClosedActionRecorder closed;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    ActionClosedHookTests(
            @Autowired Actions actions,
            @Autowired OutboundCancellation cancellation,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeProvider provider,
            @Autowired ClosedActionRecorder closed,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.actions = actions;
        this.cancellation = cancellation;
        this.executeAction = executeAction;
        this.provider = provider;
        this.closed = closed;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @BeforeEach
    void runLikeAWorkerThread() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aPermanentFailureIsReportedOnceInItsTransaction() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, action(FakeActionHandlers.TYPE, key, null));
        provider.script(key, FakeCall.rejected("invalid_request"));

        run(member.tenantId(), id);
        run(member.tenantId(), id); // delivered again: already failed, nothing more

        assertThat(status(id)).isEqualTo("failed");
        assertThat(closed.of(id)).containsExactly("failed|invalid_request|true");
        ClosedAction reported = closed.all(id).getFirst();
        assertThat(reported.type()).isEqualTo(FakeActionHandlers.TYPE);
        assertThat(reported.payloadJson()).contains("quote-v1");
    }

    @Test
    void onlyTheLastRetryIsReported() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID id = create(member, action(FakeActionHandlers.TYPE, key, null));
        provider.script(
                key,
                FakeCall.serverError(503),
                FakeCall.serverError(503),
                FakeCall.serverError(503),
                FakeCall.serverError(503),
                FakeCall.serverError(503),
                FakeCall.serverError(502));

        for (int attempt = 1; attempt <= 5; attempt++) {
            run(member.tenantId(), id);
            assertThat(closed.of(id))
                    .as("attempt " + attempt + " will be retried")
                    .isEmpty();
            makeDue(id);
        }
        run(member.tenantId(), id);

        assertThat(status(id)).isEqualTo("failed");
        assertThat(closed.of(id)).containsExactly("failed|http_502|true");
    }

    @Test
    void aLeaseLostOnTheLastAttemptIsReported() {
        TestData.Member member = data.member("admin");
        UUID id = create(member, action(FakeActionHandlers.TYPE, key(), null));
        admin.sql("""
                        update northstar.actions set status = 'processing', attempts = 6, next_attempt_at = null,
                               lease_expires_at = now() - interval '1 second' where id = :id""").param("id", id).update();

        run(member.tenantId(), id);

        assertThat(status(id)).isEqualTo("failed");
        assertThat(closed.of(id)).containsExactly("failed|lease_expired|true");
    }

    @Test
    void successRetriesAndAuthPausesAreNotReported() {
        TestData.Member member = data.member("admin");
        String succeeds = key();
        String retries = key();
        String pauses = key();
        UUID success = create(member, action(FakeActionHandlers.TYPE, succeeds, null));
        UUID retry = create(member, action(FakeActionHandlers.TYPE, retries, null));
        UUID pause = create(member, action(FakeActionHandlers.TYPE, pauses, null));
        provider.script(retries, FakeCall.clientTimeout());
        provider.script(pauses, FakeCall.authExpired());

        run(member.tenantId(), success);
        run(member.tenantId(), retry);
        run(member.tenantId(), pause);

        assertThat(List.of(status(success), status(retry), status(pause)))
                .containsExactly("succeeded", "pending", "paused");
        assertThat(closed.of(success)).isEmpty();
        assertThat(closed.of(retry)).isEmpty();
        assertThat(closed.of(pause)).isEmpty();
    }

    @Test
    void W32_aCancellationAtCreationIsReported() {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        optOut(contact);
        String key = key();

        UUID id = create(member, action(FakeActionHandlers.CONTACT_TYPE, key, contact));
        create(member, action(FakeActionHandlers.CONTACT_TYPE, key, contact)); // the same key again: nothing new

        assertThat(status(id)).isEqualTo("cancelled");
        assertThat(closed.of(id)).containsExactly("cancelled|contact_opted_out|true");
    }

    @Test
    void W32_aCancellationAtClaimIsReported() {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        String key = key();
        UUID id = create(member, action(FakeActionHandlers.CONTACT_TYPE, key, contact));
        optOut(contact);

        run(member.tenantId(), id);
        run(member.tenantId(), id);

        assertThat(provider.calls(key)).isZero();
        assertThat(status(id)).isEqualTo("cancelled");
        assertThat(closed.of(id)).containsExactly("cancelled|contact_opted_out|true");
    }

    @Test
    void W32_eachActionTheOptOutCancelsIsReportedAndAnAttemptInFlightIsNot() {
        TestData.Member member = data.member("admin");
        UUID contact = data.contact(member.tenantId());
        UUID pending = create(member, action(FakeActionHandlers.CONTACT_TYPE, key(), contact));
        UUID review = create(member, reviewAction(key(), contact));
        UUID inFlight = create(member, action(FakeActionHandlers.CONTACT_TYPE, key(), contact));
        admin.sql("""
                        update northstar.actions set status = 'processing', attempts = 1, next_attempt_at = null,
                               lease_expires_at = now() + interval '5 minutes' where id = :id""").param("id", inFlight).update();

        int cancelled = SystemScope.call(
                member.tenantId(),
                () -> transactions.execute(status -> cancellation.cancelForOptedOutContact(contact)));
        int again = SystemScope.call(
                member.tenantId(),
                () -> transactions.execute(status -> cancellation.cancelForOptedOutContact(contact)));

        assertThat(cancelled).isEqualTo(2);
        assertThat(again).isZero();
        assertThat(closed.of(pending)).containsExactly("cancelled|contact_opted_out|true");
        assertThat(closed.of(review)).containsExactly("cancelled|contact_opted_out|true");
        assertThat(closed.of(inFlight)).isEmpty();
    }

    @Test
    void aHookThatThrowsRollsTheClosingBack() {
        TestData.Member member = data.member("admin");
        String key = key();
        UUID failing = create(member, action(FakeActionHandlers.TYPE, key, null));
        provider.script(key, FakeCall.rejected("invalid_request"));
        UUID contact = data.contact(member.tenantId());
        UUID pending = create(member, action(FakeActionHandlers.CONTACT_TYPE, key(), contact));
        closed.failFor(failing);
        closed.failFor(pending);
        try {
            assertThatThrownBy(() -> run(member.tenantId(), failing))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Closing hook failure (test)");
            assertThat(status(failing))
                    .as("the failure was not recorded; the lease runs out and the attempt is taken over")
                    .isEqualTo("processing");

            assertThatThrownBy(() -> SystemScope.call(
                            member.tenantId(),
                            () -> transactions.execute(status -> cancellation.cancelForOptedOutContact(contact))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Closing hook failure (test)");
            assertThat(status(pending))
                    .as("the opt-out's cancellation rolls back too")
                    .isEqualTo("pending");
        } finally {
            closed.stopFailingFor(failing);
            closed.stopFailingFor(pending);
        }
    }

    // Helpers.

    private UUID create(TestData.Member member, NewAction action) {
        return TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                        (Supplier<Actions.Created>) () -> actions.create(action))
                .action()
                .id();
    }

    private static NewAction action(String type, String key, UUID recipient) {
        return new NewAction(
                type, key, ActionMode.AUTOMATIC, false, "{\"draft\": \"quote-v1\"}", null, null, recipient);
    }

    private static NewAction reviewAction(String key, UUID recipient) {
        return new NewAction(
                FakeActionHandlers.CONTACT_TYPE,
                key,
                ActionMode.APPROVAL,
                true,
                "{\"draft\": \"quote-v1\"}",
                null,
                null,
                recipient);
    }

    private void run(UUID tenantId, UUID actionId) {
        executeAction.run(new ExecuteAction(tenantId, actionId));
    }

    private void optOut(UUID contact) {
        admin.sql("update northstar.contacts set consent_state = 'opted_out', opt_out_at = now() where id = :id")
                .param("id", contact)
                .update();
    }

    private void makeDue(UUID id) {
        admin.sql("update northstar.actions set next_attempt_at = now() - interval '1 second' where id = :id")
                .param("id", id)
                .update();
    }

    private String status(UUID id) {
        return admin.sql("select status from northstar.actions where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }
}
