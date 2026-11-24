package com.northstar.devtools;

import com.northstar.api.NotFoundException;
import com.northstar.contacts.AutomationPausedException;
import com.northstar.contacts.OutboundMessages;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.TenantDirectory;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/**
 * Queues one automated text to a conversation's contact, as the engine will from Phase 3 (step 2.11): the public
 * {@link WhatsAppMessages#queueText} as the system actor, so the Phase 2 gate can show what happens to an automated
 * send. The worker sends it, checking the window and the contact's consent as it always does; this tool only queues.
 *
 * <p>What it answers: {@link Result#QUEUED}; {@link Result#REFUSED_AUTOMATION_PAUSED} while a staff member has taken
 * the conversation over (nothing is stored); {@link Result#CANCELLED_CONTACT_OPTED_OUT} when the contact opted out (the
 * action is stored cancelled, its message failed).
 */
final class QueueAutomatedText {

    static final String DEFAULT_TEXT = "Automated message from the Northstar Phase 2 gate.";

    private final WhatsAppMessages messages;
    private final TenantDirectory tenants;

    QueueAutomatedText(WhatsAppMessages messages, TenantDirectory tenants) {
        this.messages = messages;
        this.tenants = tenants;
    }

    static QueueAutomatedText on(ApplicationContext context) {
        return new QueueAutomatedText(context.getBean(WhatsAppMessages.class), context.getBean(TenantDirectory.class));
    }

    enum Result {
        QUEUED,
        REFUSED_AUTOMATION_PAUSED,
        CANCELLED_CONTACT_OPTED_OUT,
        CANCELLED
    }

    /** What happened; messageId and actionId are null when nothing was stored. */
    record Answer(Result result, UUID messageId, UUID actionId) {}

    Answer queue(UUID tenant, UUID conversation, String text) {
        return SystemScope.call(tenant, () -> {
            if (!tenants.currentTenantExists()) {
                throw new LocalToolException("unknown_tenant", "No business has this ID. Nothing was done.");
            }
            WhatsAppMessages.Queued queued;
            try {
                queued = messages.queueText(
                        conversation, text, OutboundMessages.Sender.SYSTEM, "gate:" + UUID.randomUUID());
            } catch (AutomationPausedException takenOver) {
                return new Answer(Result.REFUSED_AUTOMATION_PAUSED, null, null);
            } catch (NotFoundException missing) {
                throw new LocalToolException(
                        "conversation_not_found", "This business has no such conversation. Nothing was done.");
            }
            return new Answer(
                    resultOf(queued), queued.messageId(), queued.action().id());
        });
    }

    private static Result resultOf(WhatsAppMessages.Queued queued) {
        if (!"cancelled".equals(queued.action().status())) return Result.QUEUED;
        return "contact_opted_out".equals(queued.action().cancelReason())
                ? Result.CANCELLED_CONTACT_OPTED_OUT
                : Result.CANCELLED;
    }
}
