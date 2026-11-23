package com.northstar.contacts.internal;

import com.northstar.api.NotFoundException;
import com.northstar.contacts.ConversationInbox;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ConversationInbox} (step 2.9a). Native SQL with bind parameters and an explicit tenant filter (D7), each call in
 * its own read-only transaction. A staff member's queries also filter on their own active membership, so they use the
 * inbox indexes and the staff policies on conversations and messages (P11) stay the backstop rather than the filter;
 * a staff member's messages query starts from a conversation they may see, so messages_staff_assigned probes one
 * conversation per message of that thread only.
 *
 * <p>Fetches one item more than the page holds to know whether another page follows. Nothing here logs; a conversation
 * the caller may not see is a {@link NotFoundException}, which logs its type and ID only.
 */
@Service
class ConversationInboxService implements ConversationInbox {

    private static final String CONVERSATIONS = """
            select c.id, c.status, c.assigned_membership_id, c.automation_paused,
                   c.taken_over_by_membership_id, c.taken_over_at, c.needs_human, c.last_inbound_at,
                   c.window_expires_at, c.last_message_at,
                   northstar.opt_out_reply_until(c.tenant_id, c.id) as reply_after_opt_out_until,
                   ct.id as contact_id, ct.name as contact_name, ct.normalized_phone, ct.consent_state, ct.opt_out_at
            from northstar.conversations c
            join northstar.contacts ct on ct.tenant_id = c.tenant_id and ct.id = c.contact_id
            where c.tenant_id = :tenant""";

    // A staff member's own conversations: the same membership test as conversations_staff_assigned.
    private static final String OWN_ONLY = """

              and c.assigned_membership_id = (
                select m.id from northstar.memberships m
                where m.tenant_id = :tenant and m.user_id = :user and m.status = 'active')""";

    private static final String ONE_CONVERSATION = "\n  and c.id = :conversation";

    private static final String CONVERSATIONS_AFTER =
            "\n  and (c.last_message_at, c.id) < (cast(:at as timestamptz), cast(:after as uuid))";

    private static final String CONVERSATIONS_ORDER = "\norder by c.last_message_at desc, c.id desc\nlimit :limit";

    private static final String MESSAGES = """
            select m.id, m.conversation_id, m.direction, m.sender_type, m.sender_membership_id, m.type, m.body,
                   m.template_id, t.name as template_name, t.language as template_language,
                   m.status, m.error_code, m.sent_at, m.delivered_at, m.read_at, m.created_at
            from northstar.messages m
            left join northstar.message_templates t on t.tenant_id = m.tenant_id and t.id = m.template_id
            where m.tenant_id = :tenant and m.conversation_id = :conversation""";

    private static final String ONE_MESSAGE = "\n  and m.id = :message";

    private static final String MESSAGES_AFTER =
            "\n  and (m.created_at, m.id) < (cast(:at as timestamptz), cast(:after as uuid))";

    private static final String MESSAGES_ORDER = "\norder by m.created_at desc, m.id desc\nlimit :limit";

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    ConversationInboxService(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Page<ConversationSummary> conversations(Position after, int limit) {
        requireLimit(limit);
        Reader reader = reader();
        String sql =
                CONVERSATIONS + reader.ownOnly() + (after == null ? "" : CONVERSATIONS_AFTER) + CONVERSATIONS_ORDER;
        List<ConversationSummary> rows = readOnly.execute(status -> {
            JdbcClient.StatementSpec query = reader.bind(jdbc.sql(sql)).param("limit", limit + 1);
            if (after != null) query = position(query, after);
            return query.query((row, n) -> conversation(row)).list();
        });
        return page(rows, limit, summary -> new Position(summary.lastMessageAt(), summary.id()));
    }

    @Override
    public Optional<ConversationSummary> conversation(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Reader reader = reader();
        return readOnly.execute(status -> findConversation(reader, conversationId));
    }

    @Override
    public Page<MessageItem> messages(UUID conversationId, Position after, int limit) {
        Objects.requireNonNull(conversationId, "conversationId");
        requireLimit(limit);
        Reader reader = reader();
        String sql = MESSAGES + (after == null ? "" : MESSAGES_AFTER) + MESSAGES_ORDER;
        List<MessageItem> rows = readOnly.execute(status -> {
            if (findConversation(reader, conversationId).isEmpty()) {
                throw new NotFoundException("conversation", conversationId);
            }
            JdbcClient.StatementSpec query = jdbc.sql(sql)
                    .param("tenant", reader.tenant())
                    .param("conversation", conversationId)
                    .param("limit", limit + 1);
            if (after != null) query = position(query, after);
            return query.query((row, n) -> message(row)).list();
        });
        return page(rows, limit, item -> new Position(item.createdAt(), item.id()));
    }

    @Override
    public Optional<MessageItem> message(UUID conversationId, UUID messageId) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messageId, "messageId");
        Reader reader = reader();
        return readOnly.execute(status -> {
            if (findConversation(reader, conversationId).isEmpty()) return Optional.<MessageItem>empty();
            return jdbc.sql(MESSAGES + ONE_MESSAGE)
                    .param("tenant", reader.tenant())
                    .param("conversation", conversationId)
                    .param("message", messageId)
                    .query((row, n) -> message(row))
                    .optional();
        });
    }

    private Optional<ConversationSummary> findConversation(Reader reader, UUID conversationId) {
        return reader.bind(jdbc.sql(CONVERSATIONS + reader.ownOnly() + ONE_CONVERSATION))
                .param("conversation", conversationId)
                .query((row, n) -> conversation(row))
                .optional();
    }

    /** Who reads: an admin reads the business's conversations, a staff member only their own. */
    private record Reader(UUID tenant, UUID user, boolean staff) {
        String ownOnly() {
            return staff ? OWN_ONLY : "";
        }

        JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query) {
            query = query.param("tenant", tenant);
            return staff ? query.param("user", user) : query;
        }
    }

    private static Reader reader() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("The inbox is read by admins and staff only");
        }
        return new Reader(context.tenantId(), context.userId(), context.role() == Role.STAFF);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("A page holds 1 to " + MAX_PAGE_SIZE + " items");
        }
    }

    private static JdbcClient.StatementSpec position(JdbcClient.StatementSpec query, Position after) {
        return query.param("at", OffsetDateTime.ofInstant(after.at(), ZoneOffset.UTC))
                .param("after", after.id());
    }

    private static <T> Page<T> page(List<T> rows, int limit, Function<T, Position> positionOf) {
        if (rows.size() <= limit) return new Page<>(rows, null);
        List<T> items = rows.subList(0, limit);
        return new Page<>(items, positionOf.apply(items.getLast()));
    }

    private static ConversationSummary conversation(ResultSet row) throws SQLException {
        return new ConversationSummary(
                row.getObject("id", UUID.class),
                row.getString("status"),
                new ContactSummary(
                        row.getObject("contact_id", UUID.class),
                        row.getString("contact_name"),
                        row.getString("normalized_phone"),
                        row.getString("consent_state"),
                        instant(row, "opt_out_at")),
                row.getObject("assigned_membership_id", UUID.class),
                row.getBoolean("automation_paused"),
                row.getObject("taken_over_by_membership_id", UUID.class),
                instant(row, "taken_over_at"),
                row.getBoolean("needs_human"),
                instant(row, "last_inbound_at"),
                instant(row, "window_expires_at"),
                instant(row, "last_message_at"),
                instant(row, "reply_after_opt_out_until"));
    }

    private static MessageItem message(ResultSet row) throws SQLException {
        UUID templateId = row.getObject("template_id", UUID.class);
        String status = row.getString("status");
        return new MessageItem(
                row.getObject("id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getString("direction"),
                row.getString("sender_type"),
                row.getObject("sender_membership_id", UUID.class),
                row.getString("type"),
                row.getString("body"),
                templateId == null
                        ? null
                        : new TemplateRef(
                                templateId, row.getString("template_name"), row.getString("template_language")),
                status,
                "delivered".equals(status) || "read".equals(status),
                row.getString("error_code"),
                instant(row, "sent_at"),
                instant(row, "delivered_at"),
                instant(row, "read_at"),
                instant(row, "created_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
