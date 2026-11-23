package com.northstar.contacts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The inbox's reads (step 2.9a, build-document.md section 3): a business's WhatsApp conversations, newest activity
 * first, and a conversation's messages, newest first, each paged by a keyset position. For people only: an admin sees
 * every conversation of the business, a staff member only the ones assigned to their own active membership (P11);
 * another tenant's conversation, like another member's, is not found (P9). Each call runs in its own read-only
 * transaction in the caller's tenant scope.
 *
 * <p>The views hold message text, names and phone numbers: their toString shows IDs and states only, and none of it is
 * ever logged.
 */
public interface ConversationInbox {

    /** The most items a page may ask for. */
    int MAX_PAGE_SIZE = 100;

    /**
     * Where a page starts: strictly after this item in the list's order, which is by this time (a conversation's
     * {@code last_message_at}, a message's {@code created_at}) then by ID, both descending.
     */
    record Position(Instant at, UUID id) {
        public Position {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(id, "id");
        }
    }

    /** A page of items and the position after its last item, or null when there is nothing after it. */
    record Page<T>(List<T> items, Position next) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * The caller's conversations, newest activity first, after {@code after} (null: from the start). A conversation
     * with new activity moves to the start of the list, so a later page no longer lists it.
     *
     * @param limit 1 to {@link #MAX_PAGE_SIZE}
     */
    Page<ConversationSummary> conversations(Position after, int limit);

    /** The conversation, if the caller may see it. */
    Optional<ConversationSummary> conversation(UUID conversationId);

    /**
     * The conversation's messages, newest first, after {@code after} (null: from the newest).
     *
     * @param limit 1 to {@link #MAX_PAGE_SIZE}
     * @throws com.northstar.api.NotFoundException if the caller may not see the conversation
     */
    Page<MessageItem> messages(UUID conversationId, Position after, int limit);

    /** One message of the conversation, if the caller may see the conversation (step 2.9b: a reply as queued). */
    Optional<MessageItem> message(UUID conversationId, UUID messageId);

    /**
     * A conversation as the inbox lists it.
     *
     * @param status open or closed
     * @param automationPaused whether automation is paused for the conversation (taking over, step 2.9c)
     * @param takenOverByMembershipId the member who took the conversation over; null unless it is taken over
     * @param takenOverAt when it was taken over; null unless it is taken over
     * @param windowExpiresAt when WhatsApp's 24-hour customer-service window ends; null with no inbound message
     * @param replyAfterOptOutUntil for an opted-out contact who wrote again after their latest opt-out, when the window
     *     for a staff member's text reply ends (step 2.9b, W32); null otherwise, and in the past once it has ended
     */
    record ConversationSummary(
            UUID id,
            String status,
            ContactSummary contact,
            UUID assignedMembershipId,
            boolean automationPaused,
            UUID takenOverByMembershipId,
            Instant takenOverAt,
            boolean needsHuman,
            Instant lastInboundAt,
            Instant windowExpiresAt,
            Instant lastMessageAt,
            Instant replyAfterOptOutUntil) {

        @Override
        public String toString() {
            return "ConversationSummary[id=" + id + ", status=" + status + "]";
        }
    }

    /**
     * The conversation's contact.
     *
     * @param consentState unknown, opted_in or opted_out (the Opted out badge, W32)
     * @param optedOutAt when the contact first opted out; null unless opted out
     */
    record ContactSummary(UUID id, String name, String phone, String consentState, Instant optedOutAt) {
        @Override
        public String toString() {
            return "ContactSummary[id=" + id + ", consentState=" + consentState + "]";
        }
    }

    /**
     * A message as the inbox shows it. Inbound messages have no status. {@code delivered} is true for a delivered or a
     * read message: Meta may report read without delivered, so a read message can have no {@code deliveredAt} (step
     * 2.7). A failed message carries its fixed {@code errorCode}, including a failure Meta reported after accepting it.
     *
     * @param body the text: a text message's, a caption, an interactive reply's title, or a template message's text as
     *     rendered when it was queued; null when there is none (or retention removed it)
     * @param template the template of a template message; null for every other type
     */
    record MessageItem(
            UUID id,
            UUID conversationId,
            String direction,
            String senderType,
            UUID senderMembershipId,
            String type,
            String body,
            TemplateRef template,
            String status,
            boolean delivered,
            String errorCode,
            Instant sentAt,
            Instant deliveredAt,
            Instant readAt,
            Instant createdAt) {

        @Override
        public String toString() {
            return "MessageItem[id=" + id + ", direction=" + direction + ", type=" + type + ", status=" + status + "]";
        }
    }

    /** A template message's template. */
    record TemplateRef(UUID id, String name, String language) {}
}
