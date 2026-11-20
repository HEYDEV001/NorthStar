package com.northstar.workflow.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.EngineEvents;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link EngineEvents} (step 2.4b): {@code insert ... on conflict (tenant_id, dedupe_key) do nothing} (P1), native SQL
 * with bind parameters and the tenant bound explicitly, inside the caller's transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class EngineEventsService implements EngineEvents {

    static final String MESSAGE_RECEIVED = "message.received";

    /** The events check allows 1 to 300 characters. */
    static final int MAX_DEDUPE_KEY_LENGTH = 300;

    private static final String INSERT_EVENT = """
            insert into northstar.events (tenant_id, lead_id, type, dedupe_key, payload)
            values (?, ?, ?, ?, cast(? as jsonb))
            on conflict on constraint events_dedupe_key do nothing""";

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

        return jdbc.sql(INSERT_EVENT)
                        .param(systemTenant())
                        .param(event.leadId())
                        .param(MESSAGE_RECEIVED)
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
