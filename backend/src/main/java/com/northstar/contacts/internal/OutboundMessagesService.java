package com.northstar.contacts.internal;

import com.northstar.api.NotFoundException;
import com.northstar.contacts.DeliveryStatus;
import com.northstar.contacts.MessageToSend;
import com.northstar.contacts.OutboundContent;
import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OutboundMessages} (step 2.6a). Native SQL with bind parameters and an explicit tenant filter on top of RLS
 * (D7), as the system actor only. MANDATORY: each call joins the caller's transaction (the queueing's, the send's or the
 * one closing its action) and none opens its own.
 *
 * <p>Marking sent or failed changes only a message still queued without a provider ID, so the two never overwrite each
 * other: a message Meta accepted stays sent, and a failed one is never sent afterwards by this class.
 *
 * <p>Lock order, the same on every path: the contact, then its conversation, its actions and their messages. Queueing
 * share-locks the contact before writing anything; marking sent or failed locks only the message, inside a transaction
 * that already holds whatever comes before it; a delivery status (step 2.7) locks only the message, after its webhook
 * row.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class OutboundMessagesService implements OutboundMessages {

    /** messages_template_params allows at most 8 KB of jsonb text; checked here first so a caller gets a clear error. */
    static final int MAX_TEMPLATE_PARAMS_BYTES = 8192;

    // The conversation's contact under a shared lock, taken before anything else (none for the conversation itself):
    // the message's foreign key check then share-locks the conversation, and the inbound job locks the contact first
    // and
    // the conversation after it (steps 2.4b, 2.5), so taking them the other way round could deadlock with an opt-out.
    // The send's consent read later in this transaction takes the same lock again.
    private static final String LOCK_RECIPIENT = """
            select ct.id
            from northstar.conversations c
            join northstar.contacts ct on ct.tenant_id = c.tenant_id and ct.id = c.contact_id
            where c.tenant_id = ? and c.id = ?
            for share of ct""";

    private static final String TEMPLATE_VISIBLE = """
            select exists (select from northstar.message_templates where tenant_id = ? and id = ?)""";

    private static final String INSERT = """
            insert into northstar.messages
              (id, tenant_id, conversation_id, direction, sender_type, type, body, template_id, template_params, status)
            values (?, ?, ?, 'out', ?, ?, ?, ?, cast(? as jsonb), 'queued')
            on conflict (id) do nothing""";

    // The stored message's recipient: the contact of the conversation it is in, which for a repeated ID may be the
    // first call's conversation.
    private static final String STORED_RECIPIENT = """
            select c.contact_id
            from northstar.messages m
            join northstar.conversations c on c.tenant_id = m.tenant_id and c.id = m.conversation_id
            where m.tenant_id = ? and m.id = ?""";

    // One statement, so the message, its conversation's number and window, the contact's number and the template are
    // read together. The window is open only while it ends more than the margin from now, on the database's clock.
    private static final String TO_SEND = """
            select m.id, m.type, m.status, m.provider_message_id is not null as accepted, m.body,
                   m.template_params::text as template_params,
                   t.id as template_id, t.name as template_name, t.language as template_language,
                   t.status as template_status,
                   c.whatsapp_phone_number_id, ct.normalized_phone,
                   coalesce(c.window_expires_at > now() + cast(? as integer) * interval '1 second', false)
                     as window_open
            from northstar.messages m
            join northstar.conversations c on c.tenant_id = m.tenant_id and c.id = m.conversation_id
            join northstar.contacts ct on ct.tenant_id = c.tenant_id and ct.id = c.contact_id
            left join northstar.message_templates t on t.tenant_id = m.tenant_id and t.id = m.template_id
            where m.tenant_id = ? and m.id = ? and m.direction = 'out'""";

    private static final String MARK_SENT = """
            update northstar.messages set provider_message_id = ?, status = 'sent', sent_at = now()
            where tenant_id = ? and id = ? and direction = 'out' and status = 'queued'
              and provider_message_id is null""";

    private static final String MARK_FAILED = """
            update northstar.messages set status = 'failed', error_code = ?
            where tenant_id = ? and id = ? and direction = 'out' and status = 'queued'
              and provider_message_id is null""";

    // A delivery status (step 2.7): the outbound message Meta knows by this ID, locked alone (not its conversation),
    // with
    // what deciding the status needs.
    private static final String LOCK_BY_PROVIDER_ID = """
            select m.id, m.status, m.conversation_id, c.contact_id, m.delivered_at is null as delivered_at_empty
            from northstar.messages m
            join northstar.conversations c on c.tenant_id = m.tenant_id and c.id = m.conversation_id
            where m.tenant_id = ? and m.provider_message_id = ? and m.direction = 'out'
            for update of m""";

    // Each move is guarded by the statuses it may leave, under the lock just taken; messages_status_forward (NSM02) is
    // the backstop. Meta's time fills an empty delivered_at or read_at; one already set is kept (NSM01).
    private static final String TO_SENT = """
            update northstar.messages set status = 'sent'
            where tenant_id = ? and id = ? and status = 'queued'""";

    private static final String TO_DELIVERED = """
            update northstar.messages set status = 'delivered', delivered_at = coalesce(delivered_at, ?)
            where tenant_id = ? and id = ? and status in ('queued', 'sent')""";

    private static final String TO_READ = """
            update northstar.messages set status = 'read', read_at = coalesce(read_at, ?)
            where tenant_id = ? and id = ? and status in ('queued', 'sent', 'delivered')""";

    private static final String TO_FAILED = """
            update northstar.messages set status = 'failed', error_code = ?
            where tenant_id = ? and id = ? and status in ('queued', 'sent')""";

    private static final String LATE_DELIVERED = """
            update northstar.messages set delivered_at = ?
            where tenant_id = ? and id = ? and status = 'read' and delivered_at is null""";

    /** queued < sent < delivered < read; failed is outside the order. */
    private static final List<String> FORWARD = List.of("queued", "sent", "delivered", "read");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OutboundMessagesService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Queued queue(UUID messageId, UUID conversationId, Sender sender, OutboundContent content) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(content, "content");
        UUID tenant = systemTenant();
        if (jdbc.sql(LOCK_RECIPIENT)
                .param(tenant)
                .param(conversationId)
                .query(UUID.class)
                .optional()
                .isEmpty()) {
            throw new NotFoundException("conversation", conversationId);
        }
        String type;
        String body = null;
        UUID templateId = null;
        String parameters = null;
        switch (content) {
            case OutboundContent.Text text -> {
                type = "text";
                body = text.body();
                requireNoNul(body);
            }
            case OutboundContent.Template template -> {
                type = "template";
                templateId = template.templateId();
                if (!exists(TEMPLATE_VISIBLE, tenant, templateId)) {
                    throw new NotFoundException("message_template", templateId);
                }
                parameters = parametersJson(template.parameters());
            }
        }
        int stored = jdbc.sql(INSERT)
                .param(messageId)
                .param(tenant)
                .param(conversationId)
                .param(sender.code())
                .param(type)
                .param(body)
                .param(templateId)
                .param(parameters)
                .update();
        UUID recipient = jdbc.sql(STORED_RECIPIENT)
                .param(tenant)
                .param(messageId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("A message with this ID belongs to no conversation here"));
        return new Queued(messageId, recipient, stored == 1);
    }

    @Override
    public Optional<MessageToSend> toSend(UUID messageId, Duration windowMargin) {
        Objects.requireNonNull(messageId, "messageId");
        if (windowMargin == null || windowMargin.isNegative()) throw new IllegalArgumentException("windowMargin < 0");
        UUID tenant = systemTenant();
        return jdbc.sql(TO_SEND)
                .param(Math.toIntExact(windowMargin.toSeconds()))
                .param(tenant)
                .param(messageId)
                .query((row, n) -> toSend(row))
                .optional();
    }

    @Override
    public boolean markSent(UUID messageId, String providerMessageId) {
        Objects.requireNonNull(messageId, "messageId");
        if (providerMessageId == null || providerMessageId.isBlank() || providerMessageId.length() > 200) {
            throw new IllegalArgumentException("A provider message ID is 1 to 200 characters");
        }
        return jdbc.sql(MARK_SENT)
                        .param(providerMessageId)
                        .param(systemTenant())
                        .param(messageId)
                        .update()
                == 1;
    }

    @Override
    public boolean markFailed(UUID messageId, String errorCode) {
        Objects.requireNonNull(messageId, "messageId");
        if (errorCode == null || !errorCode.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("An error code is a fixed code");
        }
        return jdbc.sql(MARK_FAILED)
                        .param(errorCode)
                        .param(systemTenant())
                        .param(messageId)
                        .update()
                == 1;
    }

    @Override
    public StatusUpdate recordStatus(String providerMessageId, DeliveryStatus status, Instant at, String errorCode) {
        Objects.requireNonNull(providerMessageId, "providerMessageId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
        if ((status == DeliveryStatus.FAILED) != (errorCode != null)) {
            throw new IllegalArgumentException("A failed status, and only one, carries an error code");
        }
        if (errorCode != null && !errorCode.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("An error code is a fixed code");
        }
        UUID tenant = systemTenant();
        Optional<Current> found = jdbc.sql(LOCK_BY_PROVIDER_ID)
                .param(tenant)
                .param(providerMessageId)
                .query((row, n) -> new Current(
                        row.getObject("id", UUID.class),
                        row.getString("status"),
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("contact_id", UUID.class),
                        row.getBoolean("delivered_at_empty")))
                .optional();
        if (found.isEmpty()) return new NotFound();
        Current message = found.get();
        String from = message.status();
        String to = status.code();

        if (from.equals(to)) return new Unchanged(message.id(), message.conversationId(), message.contactId(), from);
        if (from.equals("failed")) return new Ignored(message.id(), from, to);
        if (status == DeliveryStatus.FAILED) {
            if (!from.equals("queued") && !from.equals("sent")) return new Ignored(message.id(), from, to);
            move(jdbc.sql(TO_FAILED).param(errorCode), tenant, message.id());
            return new Applied(message.id(), message.conversationId(), message.contactId(), from, to);
        }
        if (FORWARD.indexOf(to) > FORWARD.indexOf(from)) {
            switch (status) {
                case SENT -> move(jdbc.sql(TO_SENT), tenant, message.id());
                case DELIVERED -> move(jdbc.sql(TO_DELIVERED).param(utc(at)), tenant, message.id());
                case READ -> move(jdbc.sql(TO_READ).param(utc(at)), tenant, message.id());
                case FAILED -> throw new IllegalStateException("unreachable");
            }
            return new Applied(message.id(), message.conversationId(), message.contactId(), from, to);
        }
        if (status == DeliveryStatus.DELIVERED && from.equals("read")) {
            boolean filled = message.deliveredAtEmpty()
                    && jdbc.sql(LATE_DELIVERED)
                                    .param(utc(at))
                                    .param(tenant)
                                    .param(message.id())
                                    .update()
                            == 1;
            return new LateDelivered(message.id(), message.conversationId(), message.contactId(), filled);
        }
        return new Ignored(message.id(), from, to);
    }

    /** A message as a delivery status finds it, locked. */
    private record Current(UUID id, String status, UUID conversationId, UUID contactId, boolean deliveredAtEmpty) {}

    /** Runs one guarded move; under the row lock it must change exactly that row. */
    private static void move(JdbcClient.StatementSpec statement, UUID tenant, UUID messageId) {
        if (statement.param(tenant).param(messageId).update() != 1) {
            throw new IllegalStateException("A locked message did not take its status");
        }
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private MessageToSend toSend(ResultSet row) throws SQLException {
        String type = row.getString("type");
        MessageToSend.Template template = null;
        UUID templateId = row.getObject("template_id", UUID.class);
        if (templateId != null) {
            template = new MessageToSend.Template(
                    templateId,
                    row.getString("template_name"),
                    row.getString("template_language"),
                    row.getString("template_status"),
                    parameters(row.getString("template_params")));
        }
        return new MessageToSend(
                row.getObject("id", UUID.class),
                type,
                row.getString("status"),
                row.getBoolean("accepted"),
                row.getString("whatsapp_phone_number_id"),
                row.getString("normalized_phone"),
                row.getBoolean("window_open"),
                "text".equals(type) ? row.getString("body") : null,
                template);
    }

    /**
     * The values as stored: a JSON array (positional) or object (named) of strings. The size check allows for jsonb
     * printing a space after each comma and colon, which the database's check counts.
     */
    private String parametersJson(TemplateParameters parameters) {
        Object values;
        int separators;
        switch (parameters) {
            case TemplateParameters.Positional positional -> {
                positional.values().forEach(OutboundMessagesService::requireNoNul);
                values = positional.values();
                separators = positional.values().size();
            }
            case TemplateParameters.Named named -> {
                named.values().values().forEach(OutboundMessagesService::requireNoNul);
                values = named.values();
                separators = 2 * named.values().size();
            }
        }
        String text = json.writeValueAsString(values);
        if (text.getBytes(StandardCharsets.UTF_8).length + separators > MAX_TEMPLATE_PARAMS_BYTES) {
            throw new IllegalArgumentException("Template values are at most 8 KB");
        }
        return text;
    }

    private TemplateParameters parameters(String stored) {
        if (stored == null) return TemplateParameters.none();
        Object parsed = json.readValue(stored, Object.class);
        if (parsed instanceof List<?> list) {
            return new TemplateParameters.Positional(
                    list.stream().map(String.class::cast).toList());
        }
        if (parsed instanceof Map<?, ?> map) {
            Map<String, String> values = new LinkedHashMap<>();
            map.forEach((name, value) -> values.put((String) name, (String) value));
            return new TemplateParameters.Named(values);
        }
        throw new IllegalStateException("Stored template values are neither an array nor an object");
    }

    /** Postgres text cannot hold U+0000. */
    private static void requireNoNul(String value) {
        if (value.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Message text cannot contain U+0000");
    }

    private boolean exists(String sql, UUID tenant, UUID id) {
        return Boolean.TRUE.equals(
                jdbc.sql(sql).param(tenant).param(id).query(Boolean.class).single());
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Outbound messages are written by the system actor");
        return context.tenantId();
    }
}
