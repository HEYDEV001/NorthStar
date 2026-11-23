package com.northstar.integrations.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.contacts.OutboundContent;
import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link WhatsAppMessages} (step 2.6a): the message through contacts and the action through workflow, in one
 * transaction. The message ID is derived from the tenant and the idempotency key, and the action's key is the same key
 * with this type's prefix, so a repeat finds both instead of creating either (P1).
 *
 * <p>An action for an opted-out contact is created cancelled (step 2.5); the send handler hears of it in this same
 * transaction and marks the message failed with {@code contact_opted_out}.
 */
@Service
class WhatsAppMessageQueue implements WhatsAppMessages {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageQueue.class);

    /** The action's idempotency key is this prefix and the caller's key, so no other type's key can collide with it. */
    static final String KEY_PREFIX = "whatsapp-send:";

    /** actions.idempotency_key is at most 200 characters, the prefix included. */
    static final int MAX_KEY_LENGTH = 200 - KEY_PREFIX.length();

    /** A staff reply's key, within the caller's: "staff:", the member's membership ID, ":" and the request's key. */
    static final String STAFF_KEY_PREFIX = "staff:";

    /** The audit entry for each reply sent to an opted-out contact under the staff reply exception (step 2.9b). */
    static final String SENT_AFTER_OPT_OUT = "message.sent_after_opt_out";

    private final OutboundMessages messages;
    private final Actions actions;
    private final TenantDirectory tenants;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;

    WhatsAppMessageQueue(
            OutboundMessages messages,
            Actions actions,
            TenantDirectory tenants,
            AuditTrail audit,
            PlatformTransactionManager transactionManager) {
        this.messages = messages;
        this.actions = actions;
        this.tenants = tenants;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Queued queueText(UUID conversationId, String text, OutboundMessages.Sender sender, String idempotencyKey) {
        return queue(conversationId, new OutboundContent.Text(text), sender, idempotencyKey);
    }

    @Override
    public Queued queueTemplate(
            UUID conversationId,
            UUID templateId,
            TemplateParameters parameters,
            OutboundMessages.Sender sender,
            String idempotencyKey) {
        return queue(conversationId, new OutboundContent.Template(templateId, parameters), sender, idempotencyKey);
    }

    @Override
    public Queued queueStaffReply(UUID conversationId, OutboundContent content, String idempotencyKey) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_STAFF_KEY_LENGTH) {
            throw new IllegalArgumentException("A staff reply's key is 1 to " + MAX_STAFF_KEY_LENGTH + " characters");
        }
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Staff replies are queued by admins and staff only");
        }
        UUID membership = tenants.currentMembershipId()
                .orElseThrow(() -> new ForbiddenException("no_active_membership", "This account has no membership"));
        String key = STAFF_KEY_PREFIX + membership + ":" + idempotencyKey;
        UUID messageId = messageIdFor(context.tenantId(), key);
        return transactions.execute(status -> {
            OutboundMessages.StaffReplyQueued message = messages.queueStaffReply(
                    messageId, conversationId, content, WhatsAppSendHandler.WINDOW_SAFETY_MARGIN);
            Actions.Created created = actions.create(new NewAction(
                    SEND_TYPE,
                    KEY_PREFIX + key,
                    ActionMode.AUTOMATIC,
                    false,
                    WhatsAppSendHandler.payloadFor(messageId),
                    null,
                    null,
                    message.recipientContactId()));
            if (message.stored() && message.afterOptOut()) audit.record(SENT_AFTER_OPT_OUT, "messages", messageId);
            log.info(
                    "whatsapp.send.staff_reply_queued message_id={} action_id={} created={} status={} after_opt_out={}"
                            + " reopened={}",
                    messageId,
                    created.action().id(),
                    message.stored(),
                    created.action().status(),
                    message.afterOptOut(),
                    message.reopened());
            return new Queued(messageId, created.action(), message.stored());
        });
    }

    /** The message's ID for this send: the same tenant and key always give the same ID, and nothing else gives it. */
    static UUID messageIdFor(UUID tenantId, String idempotencyKey) {
        return UUID.nameUUIDFromBytes(
                ("whatsapp-send:v1|" + tenantId + "|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    private Queued queue(
            UUID conversationId, OutboundContent content, OutboundMessages.Sender sender, String idempotencyKey) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("A send's idempotency key is 1 to " + MAX_KEY_LENGTH + " characters");
        }
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) {
            throw new IllegalStateException("System and AI messages are queued by the system actor");
        }
        UUID messageId = messageIdFor(context.tenantId(), idempotencyKey);
        return transactions.execute(status -> {
            OutboundMessages.Queued message = messages.queue(messageId, conversationId, sender, content);
            Actions.Created created = actions.create(new NewAction(
                    SEND_TYPE,
                    KEY_PREFIX + idempotencyKey,
                    ActionMode.AUTOMATIC,
                    false,
                    WhatsAppSendHandler.payloadFor(messageId),
                    null,
                    null,
                    message.recipientContactId()));
            log.info(
                    "whatsapp.send.queued message_id={} action_id={} created={} status={}",
                    messageId,
                    created.action().id(),
                    created.created(),
                    created.action().status());
            return new Queued(messageId, created.action(), created.created());
        });
    }
}
