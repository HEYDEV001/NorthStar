package com.northstar.workflow.internal;

import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineEvents;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link EngineEvents} (steps 2.4b, 2.5 and 2.7): {@code insert ... on conflict (tenant_id, dedupe_key) do nothing} (P1),
 * native SQL with bind parameters and the tenant bound explicitly, inside the caller's transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class EngineEventsService implements EngineEvents {

    static final String MESSAGE_RECEIVED = "message.received";
    static final String OPTOUT_RECEIVED = "optout.received";
    static final String MESSAGE_STATUS = "message.status";
    static final String STAFF_ACTION = "staff.action";

    /** The events check allows 1 to 300 characters. */
    static final int MAX_DEDUPE_KEY_LENGTH = 300;

    private static final String INSERT_EVENT = """
            insert into northstar.events (tenant_id, lead_id, type, dedupe_key, payload)
            values (?, ?, ?, ?, cast(? as jsonb))
            on conflict on constraint events_dedupe_key do nothing""";

    // The contact's open lead as the caller sees it: leads_staff_assigned shows a staff member only their own.
    private static final String OPEN_LEAD = """
            select id from northstar.leads
            where tenant_id = ? and contact_id = ? and closed_at is null and deleted_at is null""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    EngineEventsService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean recordMessageReceived(MessageReceived event) {
        Objects.requireNonNull(event, "event");
        String dedupeKey = MESSAGE_RECEIVED + ":" + event.providerMessageId();
        if (dedupeKey.length() > MAX_DEDUPE_KEY_LENGTH) throw new IllegalArgumentException("Dedupe key too long");

        // IDs, fixed codes and WhatsApp's timestamp only (the record has nothing else to give).
        ObjectNode payload = json.createObjectNode();
        payload.put("webhook_event_id", event.webhookEventId().toString());
        payload.put("contact_id", event.contactId().toString());
        payload.put("conversation_id", event.conversationId().toString());
        payload.put("message_id", event.messageId().toString());
        payload.put("message_type", event.messageType());
        payload.put("lead", event.lead().code());
        payload.put("sent_at", event.sentAt().toString());

        return insert(event.leadId(), MESSAGE_RECEIVED, dedupeKey, payload);
    }

    @Override
    public boolean recordOptOutReceived(OptOutReceived event) {
        Objects.requireNonNull(event, "event");
        String dedupeKey = OPTOUT_RECEIVED + ":" + event.providerMessageId();
        if (dedupeKey.length() > MAX_DEDUPE_KEY_LENGTH) throw new IllegalArgumentException("Dedupe key too long");

        // IDs, a fixed code and WhatsApp's timestamp only: never the text, and not the keyword it matched.
        ObjectNode payload = json.createObjectNode();
        payload.put("webhook_event_id", event.webhookEventId().toString());
        payload.put("contact_id", event.contactId().toString());
        payload.put("conversation_id", event.conversationId().toString());
        payload.put("message_id", event.messageId().toString());
        payload.put("lead", event.lead().code());
        payload.put("sent_at", event.sentAt().toString());

        return insert(event.leadId(), OPTOUT_RECEIVED, dedupeKey, payload);
    }

    @Override
    public boolean recordMessageStatus(MessageStatus event) {
        Objects.requireNonNull(event, "event");
        String dedupeKey = MESSAGE_STATUS + ":" + event.providerMessageId() + ":" + event.status();
        if (dedupeKey.length() > MAX_DEDUPE_KEY_LENGTH) throw new IllegalArgumentException("Dedupe key too long");

        // IDs, fixed codes and Meta's timestamp only: never Meta's error title, message or details.
        ObjectNode payload = json.createObjectNode();
        payload.put("webhook_event_id", event.webhookEventId().toString());
        payload.put("contact_id", event.contactId().toString());
        payload.put("conversation_id", event.conversationId().toString());
        payload.put("message_id", event.messageId().toString());
        payload.put("status", event.status());
        if (event.errorCode() != null) payload.put("error_code", event.errorCode());
        payload.put("status_at", event.statusAt().toString());

        return insert(null, MESSAGE_STATUS, dedupeKey, payload);
    }

    @Override
    public boolean recordStaffAction(StaffAction event) {
        Objects.requireNonNull(event, "event");
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Staff actions are recorded for admins and staff only");
        }
        UUID tenant = context.tenantId();
        Optional<UUID> lead = jdbc.sql(OPEN_LEAD)
                .param(tenant)
                .param(event.contactId())
                .query(UUID.class)
                .optional();
        if (lead.isEmpty()) return false;
        String dedupeKey = STAFF_ACTION + ":" + event.kind().code() + ":" + event.conversationId() + ":"
                + ChronoUnit.MICROS.between(Instant.EPOCH, event.takeOverAt());

        // IDs, fixed codes, a count and a time only.
        ObjectNode payload = json.createObjectNode();
        payload.put("action", event.kind().code());
        payload.put("reason", event.kind().reason());
        payload.put("conversation_id", event.conversationId().toString());
        payload.put("contact_id", event.contactId().toString());
        payload.put("membership_id", event.membershipId().toString());
        payload.put("cancelled_sends", event.cancelledSends());
        payload.put("at", event.at().toString());

        return insert(tenant, lead.get(), STAFF_ACTION, dedupeKey, payload);
    }

    private boolean insert(UUID leadId, String type, String dedupeKey, ObjectNode payload) {
        return insert(systemTenant(), leadId, type, dedupeKey, payload);
    }

    private boolean insert(UUID tenant, UUID leadId, String type, String dedupeKey, ObjectNode payload) {
        return jdbc.sql(INSERT_EVENT)
                        .param(tenant)
                        .param(leadId)
                        .param(type)
                        .param(dedupeKey)
                        .param(json.writeValueAsString(payload))
                        .update()
                == 1;
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Engine events are recorded by the system actor");
        return context.tenantId();
    }
}
