package com.northstar.crm.internal;

import com.northstar.contacts.ConversationInbox;
import com.northstar.workflow.Timeline;
import java.util.UUID;

/**
 * A lead's timeline cursor (step 3.3b): the inbox's encoding ({@link InboxCursor}), scoped to the lead, so it pages
 * that lead's timeline only; anything else is 400 {@value InboxCursor#INVALID}.
 */
final class TimelineCursor {

    private TimelineCursor() {}

    static String encode(UUID leadId, Timeline.Position next) {
        return next == null
                ? null
                : InboxCursor.encode(scope(leadId), new ConversationInbox.Position(next.occurredAt(), next.id()));
    }

    /** The position a cursor names for this lead; null (the first page) when there is no cursor. */
    static Timeline.Position decode(UUID leadId, String cursor) {
        ConversationInbox.Position position = InboxCursor.decode(scope(leadId), cursor);
        return position == null ? null : new Timeline.Position(position.at(), position.id());
    }

    private static String scope(UUID leadId) {
        return "timeline:" + leadId;
    }
}
