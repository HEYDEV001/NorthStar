package com.northstar.crm.internal;

import com.northstar.contacts.ConversationInbox.ContactSummary;
import com.northstar.contacts.ConversationInbox.ConversationSummary;
import com.northstar.contacts.ConversationInbox.MessageItem;
import com.northstar.contacts.ConversationInbox.TemplateRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The inbox API's response bodies (step 2.9a): JSON with ISO-8601 UTC times, every field always present (null when
 * empty), so a client's schema can be exact. They carry message text, names and phone numbers to the people allowed to
 * see them, so their toString shows IDs and states only.
 */
final class InboxResponses {

    private InboxResponses() {}

    /** A page: its items, and the cursor for the next page, null on the last one. */
    record PageResponse<T>(List<T> items, String nextCursor) {}

    record ConversationResponse(
            UUID id,
            String status,
            ContactResponse contact,
            UUID assignedMembershipId,
            boolean automationPaused,
            boolean needsHuman,
            Instant lastInboundAt,
            Instant windowExpiresAt,
            Instant lastMessageAt) {

        static ConversationResponse of(ConversationSummary conversation) {
            return new ConversationResponse(
                    conversation.id(),
                    conversation.status(),
                    ContactResponse.of(conversation.contact()),
                    conversation.assignedMembershipId(),
                    conversation.automationPaused(),
                    conversation.needsHuman(),
                    conversation.lastInboundAt(),
                    conversation.windowExpiresAt(),
                    conversation.lastMessageAt());
        }

        @Override
        public String toString() {
            return "ConversationResponse[id=" + id + ", status=" + status + "]";
        }
    }

    /** The conversation's contact; consentState drives the Opted out badge (W32). */
    record ContactResponse(UUID id, String name, String phone, String consentState, Instant optedOutAt) {
        static ContactResponse of(ContactSummary contact) {
            return new ContactResponse(
                    contact.id(), contact.name(), contact.phone(), contact.consentState(), contact.optedOutAt());
        }

        @Override
        public String toString() {
            return "ContactResponse[id=" + id + ", consentState=" + consentState + "]";
        }
    }

    record MessageResponse(
            UUID id,
            UUID conversationId,
            String direction,
            String senderType,
            UUID senderMembershipId,
            String type,
            String body,
            TemplateResponse template,
            String status,
            boolean delivered,
            String errorCode,
            Instant sentAt,
            Instant deliveredAt,
            Instant readAt,
            Instant createdAt) {

        static MessageResponse of(MessageItem message) {
            return new MessageResponse(
                    message.id(),
                    message.conversationId(),
                    message.direction(),
                    message.senderType(),
                    message.senderMembershipId(),
                    message.type(),
                    message.body(),
                    TemplateResponse.of(message.template()),
                    message.status(),
                    message.delivered(),
                    message.errorCode(),
                    message.sentAt(),
                    message.deliveredAt(),
                    message.readAt(),
                    message.createdAt());
        }

        @Override
        public String toString() {
            return "MessageResponse[id=" + id + ", direction=" + direction + ", type=" + type + ", status=" + status
                    + "]";
        }
    }

    record TemplateResponse(UUID id, String name, String language) {
        static TemplateResponse of(TemplateRef template) {
            return template == null ? null : new TemplateResponse(template.id(), template.name(), template.language());
        }
    }
}
