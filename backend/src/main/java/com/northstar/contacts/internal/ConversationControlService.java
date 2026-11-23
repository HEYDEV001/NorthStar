package com.northstar.contacts.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.contacts.ConversationControl;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ConversationControl} (step 2.9c). Native SQL with bind parameters and an explicit tenant filter on top of RLS
 * (D7); conversations_staff_assigned keeps a staff member to their own conversations, so another member's is not
 * found, as another tenant's is. MANDATORY: it joins the caller's transaction, in which the caller then cancels the
 * automated sends, so the take over and the cancellations commit together.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ConversationControlService implements ConversationControl {

    // The contact first, shared, as every path that touches a conversation's messages takes it (OutboundMessages).
    private static final String LOCK_CONTACT = """
            select ct.id
            from northstar.conversations c
            join northstar.contacts ct on ct.tenant_id = c.tenant_id and ct.id = c.contact_id
            where c.tenant_id = ? and c.id = ?
            for share of ct""";

    private static final String LOCK_CONVERSATION = """
            select taken_over_by_membership_id, taken_over_at
            from northstar.conversations
            where tenant_id = ? and id = ?
            for no key update""";

    private static final String TAKE_OVER = """
            update northstar.conversations
            set automation_paused = true, taken_over_by_membership_id = ?, taken_over_at = now()
            where tenant_id = ? and id = ? and taken_over_at is null
            returning taken_over_at""";

    private static final String HAND_BACK = """
            update northstar.conversations
            set automation_paused = false, taken_over_by_membership_id = null, taken_over_at = null
            where tenant_id = ? and id = ? and taken_over_at is not null""";

    // The sends a take over cancels: the conversation's outbound messages from the system or the AI still queued.
    // A staff member's are left alone.
    private static final String QUEUED_AUTOMATED = """
            select id from northstar.messages
            where tenant_id = ? and conversation_id = ? and direction = 'out' and status = 'queued'
              and sender_type in ('system', 'ai')
            order by id""";

    private final JdbcClient jdbc;
    private final TenantDirectory directory;

    ConversationControlService(JdbcClient jdbc, TenantDirectory directory) {
        this.jdbc = jdbc;
        this.directory = directory;
    }

    @Override
    public TakeOver takeOver(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        UUID tenant = memberTenant();
        UUID member = directory
                .currentMembershipId()
                .orElseThrow(() -> new ForbiddenException("no_active_membership", "This account has no membership"));
        UUID contact = lock(tenant, conversationId);
        State before = state(tenant, conversationId);
        if (before.takenOverAt() != null) {
            return new TakeOver(conversationId, contact, false, before.takenOverBy(), before.takenOverAt(), List.of());
        }
        OffsetDateTime at = jdbc.sql(TAKE_OVER)
                .param(member)
                .param(tenant)
                .param(conversationId)
                .query(OffsetDateTime.class)
                .single();
        List<UUID> queued = jdbc.sql(QUEUED_AUTOMATED)
                .param(tenant)
                .param(conversationId)
                .query(UUID.class)
                .list();
        return new TakeOver(conversationId, contact, true, member, at.toInstant(), queued);
    }

    @Override
    public HandBack handBack(UUID conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        UUID tenant = memberTenant();
        UUID contact = lock(tenant, conversationId);
        State before = state(tenant, conversationId);
        if (before.takenOverAt() == null) return new HandBack(conversationId, contact, false, null);
        jdbc.sql(HAND_BACK).param(tenant).param(conversationId).update();
        return new HandBack(conversationId, contact, true, before.takenOverAt());
    }

    /** The take over as it stands, read under the conversation's lock. */
    private record State(UUID takenOverBy, Instant takenOverAt) {}

    /** Takes the contact's shared lock, then the conversation's; returns the contact. */
    private UUID lock(UUID tenant, UUID conversationId) {
        Optional<UUID> contact = jdbc.sql(LOCK_CONTACT)
                .param(tenant)
                .param(conversationId)
                .query(UUID.class)
                .optional();
        return contact.orElseThrow(() -> new NotFoundException("conversation", conversationId));
    }

    private State state(UUID tenant, UUID conversationId) {
        return jdbc.sql(LOCK_CONVERSATION)
                .param(tenant)
                .param(conversationId)
                .query((row, n) -> {
                    OffsetDateTime at = row.getObject("taken_over_at", OffsetDateTime.class);
                    return new State(
                            row.getObject("taken_over_by_membership_id", UUID.class),
                            at == null ? null : at.toInstant());
                })
                .single();
    }

    private static UUID memberTenant() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Conversations are taken over and handed back by admins and staff only");
        }
        return context.tenantId();
    }
}
