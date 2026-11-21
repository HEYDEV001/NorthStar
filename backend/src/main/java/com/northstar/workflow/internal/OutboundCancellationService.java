package com.northstar.workflow.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ClosedAction;
import com.northstar.workflow.OutboundCancellation;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OutboundCancellation} (step 2.5). One native update with bind parameters and an explicit tenant filter on top
 * of RLS (D7), served by the partial index actions_open_by_recipient. MANDATORY: it joins the inbound job's
 * transaction, which already holds the contact's lock, so it commits with the opt-out or not at all.
 *
 * <p>An action being claimed at the same moment holds its row lock; the update waits for it and then re-reads the
 * row, so a claim that has just made it processing is left alone (its next claim is refused) and one that has just put
 * it back to pending for a retry is cancelled.
 *
 * <p>Each cancelled action is reported to its handler's {@link com.northstar.workflow.ActionHandler#closed} in the same
 * transaction (step 2.6a), so a send's queued message is marked failed with the opt-out.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class OutboundCancellationService implements OutboundCancellation {

    private static final String CANCEL = """
            update northstar.actions
            set status = 'cancelled', cancel_reason = ?, next_attempt_at = null
            where tenant_id = ? and recipient_contact_id = ? and status in ('pending', 'needs_review', 'paused')
            returning id, type, payload::text""";

    private final JdbcClient jdbc;
    private final ActionHandlers handlers;

    OutboundCancellationService(JdbcClient jdbc, ActionHandlers handlers) {
        this.jdbc = jdbc;
        this.handlers = handlers;
    }

    private record Cancelled(UUID id, String type, String payload) {}

    @Override
    public int cancelForOptedOutContact(UUID contactId) {
        Objects.requireNonNull(contactId, "contactId");
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Opt-outs cancel actions as the system actor");
        List<Cancelled> cancelled = jdbc.sql(CANCEL)
                .param(CONTACT_OPTED_OUT)
                .param(context.tenantId())
                .param(contactId)
                .query((row, n) ->
                        new Cancelled(row.getObject("id", UUID.class), row.getString("type"), row.getString("payload")))
                .list();
        for (Cancelled action : cancelled) {
            handlers.reportClosed(
                    action.id(), action.type(), action.payload(), ClosedAction.Closing.CANCELLED, CONTACT_OPTED_OUT);
        }
        return cancelled.size();
    }
}
