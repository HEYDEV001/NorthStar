package com.northstar.integrations.internal;

import com.northstar.contacts.ContactSource;
import com.northstar.contacts.InboundContacts;
import com.northstar.workflow.EngineEvents;
import com.northstar.workflow.EngineEvents.LeadOutcome;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.LeadIntake.OpenLead;
import com.northstar.workflow.LeadOrigin;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Processes one stored WhatsApp webhook event (step 2.4b; integration-specs.md section 1, step 6), all inside the
 * caller's one transaction as the system actor in the event's tenant, so it is stored in full or not at all (P3):
 *
 * <ol>
 *   <li>lock the event row: one already processed is left alone (P1), and a status row waits for step 2.7;
 *   <li>read the message from the stored slice; an unusable one is closed with a fixed code and nothing else is
 *       written (P4);
 *   <li>a message already stored is a duplicate: the row is closed and nothing else is written (P1);
 *   <li>the contact, created if new and locked, so one contact's messages are processed one at a time (P3);
 *   <li>its source ({@code direct} until Phase 7), its open lead, its conversation (with the open lead's assignee if
 *       new), the conversation's last inbound time, and the message;
 *   <li>the lead: the open lead is joined, and linked to the conversation if it had none (W1); otherwise a new lead
 *       is opened in New on the tenant's published version with the conversation's assignee (W2, P3), unless the
 *       message is a reaction or system message, or no workflow is published ({@value #NO_PUBLISHED_WORKFLOW});
 *   <li>the engine event {@code message.received}, with IDs and codes only;
 *   <li>processed_at.
 * </ol>
 *
 * <p>Nothing here logs; the job logs the outcome, with IDs and codes only, after the commit.
 */
@Component
class WhatsAppInboundProcessor {

    static final String PROVIDER = WhatsAppWebhookIntake.PROVIDER;
    static final String STATUS_EVENT_PREFIX = "status:";

    /** Processed in full, but no lead was opened because the tenant has no single published workflow. */
    static final String NO_PUBLISHED_WORKFLOW = "no_published_workflow";

    /** What processing one event did. */
    sealed interface Outcome {}

    /** The job's tenant has no such event: a bug, as the job exists only for its own row. */
    record Missing() implements Outcome {}

    record AlreadyProcessed() implements Outcome {}

    /** A delivery status, left untouched for step 2.7. */
    record StatusDeferred() implements Outcome {}

    /** Closed for good with this fixed code; nothing else was written. */
    record Rejected(String errorCode) implements Outcome {}

    /** The message was already stored (by an earlier run); closed with nothing else written. */
    record DuplicateMessage() implements Outcome {}

    /** Stored: the message, and the lead it opened or joined (null for none). */
    record Stored(UUID messageId, UUID leadId, LeadOutcome lead, boolean noPublishedWorkflow) implements Outcome {}

    private final WebhookEventProcessing rows;
    private final InboundContacts contacts;
    private final LeadIntake leads;
    private final EngineEvents events;

    WhatsAppInboundProcessor(
            WebhookEventProcessing rows, InboundContacts contacts, LeadIntake leads, EngineEvents events) {
        this.rows = rows;
        this.contacts = contacts;
        this.leads = leads;
        this.events = events;
    }

    /** Runs inside the caller's transaction. */
    Outcome process(UUID webhookEventId) {
        Optional<WebhookEventProcessing.Locked> locked = rows.lock(webhookEventId, PROVIDER);
        if (locked.isEmpty()) return new Missing();
        WebhookEventProcessing.Locked row = locked.get();
        if (row.processed()) return new AlreadyProcessed();
        if (row.providerEventId().startsWith(STATUS_EVENT_PREFIX)) return new StatusDeferred();

        WhatsAppInboundMessage message;
        switch (WhatsAppInboundMessage.parse(row.providerEventId(), row.payloadJson())) {
            case WhatsAppInboundMessage.Rejected rejected -> {
                rows.finish(webhookEventId, rejected.errorCode());
                return new Rejected(rejected.errorCode());
            }
            case WhatsAppInboundMessage.Usable usable -> message = usable.message();
        }
        if (contacts.hasInboundMessage(message.wamid())) {
            rows.finish(webhookEventId, null);
            return new DuplicateMessage();
        }

        UUID contact = contacts.lockContact(message.from(), message.profileName());
        contacts.recordSource(contact, ContactSource.DIRECT, message.sentAt());
        Optional<OpenLead> open = leads.findOpenLead(contact);
        InboundContacts.Conversation conversation = contacts.lockConversation(
                contact,
                message.phoneNumberId(),
                open.map(OpenLead::assignedMembershipId).orElse(null));
        contacts.recordInboundAt(conversation.id(), message.sentAt());
        UUID messageId = contacts.storeInbound(conversation.id(), message.toStored());

        UUID leadId = null;
        LeadOutcome lead = LeadOutcome.NONE;
        boolean noPublishedWorkflow = false;
        if (open.isPresent()) {
            leadId = leads.linkConversation(open.get(), conversation.id()).id();
            lead = LeadOutcome.JOINED;
        } else if (message.mayOpenLead()) {
            Optional<UUID> version = leads.publishedWorkflowVersion();
            if (version.isPresent()) {
                OpenLead opened = leads.open(new LeadIntake.NewLead(
                        contact,
                        conversation.id(),
                        version.get(),
                        LeadOrigin.DIRECT,
                        conversation.assignedMembershipId()));
                leadId = opened.id();
                lead = opened.created() ? LeadOutcome.OPENED : LeadOutcome.JOINED;
            } else {
                noPublishedWorkflow = true;
            }
        }

        events.recordMessageReceived(new EngineEvents.MessageReceived(
                message.wamid(),
                leadId,
                lead,
                webhookEventId,
                contact,
                conversation.id(),
                messageId,
                message.type().code(),
                message.sentAt()));
        rows.finish(webhookEventId, noPublishedWorkflow ? NO_PUBLISHED_WORKFLOW : null);
        return new Stored(messageId, leadId, lead, noPublishedWorkflow);
    }
}
