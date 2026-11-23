package com.northstar.crm.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.contacts.ConversationControl;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.workflow.EngineEvents;
import com.northstar.workflow.EngineEvents.StaffActionKind;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Taking a conversation over and handing it back (step 2.9c, build-document.md sections 3 and 15: the per-conversation
 * kill switch), each in one transaction as the admin or staff member who asks:
 *
 * <ul>
 *   <li>take over: automation paused for the conversation, the member and the time recorded, and its queued automated
 *       sends (from the system or the AI; pending, waiting for review or paused) cancelled as
 *       {@code conversation_taken_over}, their messages failed with it; staff replies, and a send already being
 *       attempted, are left alone. Already taken over: nothing changes;
 *   <li>hand back: automation resumes and the take over is cleared; nothing it cancelled comes back. Not taken over:
 *       nothing changes. Only this hands a conversation back: no message, reply or timer does.
 * </ul>
 *
 * <p>Each change writes an audit entry ({@code conversation.taken_over} or {@code conversation.handed_back}, from and to
 * {@code automated} and {@code taken_over}) and, when the conversation's contact has an open lead the member can see, a
 * {@code staff.action} engine event with its fixed reason. A repeat changes nothing and writes neither.
 *
 * <p>Lock order: the contact (shared) and the conversation ({@link ConversationControl}), then the sends' actions in ID
 * order and their messages ({@link WhatsAppMessages#cancelQueuedSends}), as an opt-out and a template's status change
 * take them. Logs carry IDs, codes and counts only.
 */
@Service
class ConversationTakeover {

    private static final Logger log = LoggerFactory.getLogger(ConversationTakeover.class);

    static final String TAKEN_OVER = "conversation.taken_over";
    static final String HANDED_BACK = "conversation.handed_back";
    static final String AUTOMATED = "automated";
    static final String TAKEN_OVER_STATE = "taken_over";

    private final ConversationControl control;
    private final WhatsAppMessages whatsApp;
    private final EngineEvents events;
    private final AuditTrail audit;
    private final TenantDirectory tenants;
    private final TransactionTemplate transactions;

    ConversationTakeover(
            ConversationControl control,
            WhatsAppMessages whatsApp,
            EngineEvents events,
            AuditTrail audit,
            TenantDirectory tenants,
            PlatformTransactionManager transactionManager) {
        this.control = control;
        this.whatsApp = whatsApp;
        this.events = events;
        this.audit = audit;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What a take over or hand back did, for its log line. */
    private record Done(boolean changed, int cancelledSends, boolean event) {}

    void takeOver(UUID conversationId) {
        Done done = transactions.execute(status -> {
            ConversationControl.TakeOver taken = control.takeOver(conversationId);
            if (!taken.changed()) return new Done(false, 0, false);
            int cancelled =
                    whatsApp.cancelQueuedSends(taken.queuedAutomatedMessages(), StaffActionKind.TAKE_OVER.reason());
            audit.recordChange(TAKEN_OVER, "conversations", conversationId, AUTOMATED, TAKEN_OVER_STATE);
            boolean event = events.recordStaffAction(new EngineEvents.StaffAction(
                    StaffActionKind.TAKE_OVER,
                    conversationId,
                    taken.contactId(),
                    taken.takenOverByMembershipId(),
                    cancelled,
                    taken.takenOverAt(),
                    taken.takenOverAt()));
            return new Done(true, cancelled, event);
        });
        if (done.changed()) {
            log.info(
                    "conversation.taken_over conversation_id={} cancelled_sends={} staff_action_event={}",
                    conversationId,
                    done.cancelledSends(),
                    done.event());
        } else {
            log.info("conversation.take_over_unchanged conversation_id={}", conversationId);
        }
    }

    void handBack(UUID conversationId) {
        UUID member = tenants.currentMembershipId()
                .orElseThrow(() -> new ForbiddenException("no_active_membership", "This account has no membership"));
        Done done = transactions.execute(status -> {
            ConversationControl.HandBack handed = control.handBack(conversationId);
            if (!handed.changed()) return new Done(false, 0, false);
            audit.recordChange(HANDED_BACK, "conversations", conversationId, TAKEN_OVER_STATE, AUTOMATED);
            boolean event = events.recordStaffAction(new EngineEvents.StaffAction(
                    StaffActionKind.HAND_BACK,
                    conversationId,
                    handed.contactId(),
                    member,
                    0,
                    Instant.now().truncatedTo(ChronoUnit.MICROS),
                    handed.endedTakeOverAt()));
            return new Done(true, 0, event);
        });
        if (done.changed()) {
            log.info("conversation.handed_back conversation_id={} staff_action_event={}", conversationId, done.event());
        } else {
            log.info("conversation.hand_back_unchanged conversation_id={}", conversationId);
        }
    }
}
