package com.northstar.contacts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Taking a conversation over and handing it back (step 2.9c, build-document.md section 3: an admin takes over any
 * conversation, a staff member their assigned ones). Taking over pauses automation for the conversation
 * ({@code automation_paused}) and records who and when; only handing it back resumes it. Every method runs only inside
 * the caller's transaction (none is opened here), in the current tenant, and only for an admin or a staff member, who
 * is the member recorded; a conversation the caller cannot see is not found (P9, P11).
 *
 * <p>Lock order, as every path that touches a conversation's messages: the conversation's contact (shared), then the
 * conversation; the caller then cancels the sends of {@link TakeOver#queuedAutomatedMessages()}, locking their actions
 * and then their messages.
 */
public interface ConversationControl {

    /**
     * The conversation after a take over: changed is false when it was already taken over, by this member or another
     * (nothing changes, and the first take over's member and time stay). queuedAutomatedMessages are the conversation's
     * outbound messages from the system or the AI still queued, in ID order: the sends the caller cancels. Empty when
     * nothing changed.
     */
    record TakeOver(
            UUID conversationId,
            UUID contactId,
            boolean changed,
            UUID takenOverByMembershipId,
            Instant takenOverAt,
            List<UUID> queuedAutomatedMessages) {

        public TakeOver {
            queuedAutomatedMessages = List.copyOf(queuedAutomatedMessages);
        }
    }

    /**
     * The conversation after a hand back: changed is false when it was not taken over. endedTakeOverAt is when the take
     * over it ended began (null when nothing changed), which names that take over.
     */
    record HandBack(UUID conversationId, UUID contactId, boolean changed, Instant endedTakeOverAt) {}

    /**
     * Takes the conversation over for the caller: automation paused, the caller's active membership and now recorded,
     * unless it is already taken over.
     *
     * @throws com.northstar.api.NotFoundException if the caller cannot see the conversation
     */
    TakeOver takeOver(UUID conversationId);

    /**
     * Hands the conversation back: automation resumes and the take over's member and time are cleared, if it was taken
     * over. Nothing cancelled while it was taken over comes back.
     *
     * @throws com.northstar.api.NotFoundException if the caller cannot see the conversation
     */
    HandBack handBack(UUID conversationId);
}
