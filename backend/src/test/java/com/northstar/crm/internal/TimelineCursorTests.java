package com.northstar.crm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.api.BadRequestException;
import com.northstar.contacts.ConversationInbox;
import com.northstar.workflow.Timeline;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A lead's timeline cursor (step 3.3b): it round-trips, and pages only the lead it was issued for. */
class TimelineCursorTests {

    @Test
    void aCursorRoundTripsToTheMicrosecond() {
        UUID lead = UUID.randomUUID();
        Timeline.Position position =
                new Timeline.Position(Instant.parse("2026-11-28T10:15:30.123456Z"), UUID.randomUUID());

        String cursor = TimelineCursor.encode(lead, position);

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(TimelineCursor.decode(lead, cursor)).isEqualTo(position);
        assertThat(TimelineCursor.encode(lead, null)).isNull();
        assertThat(TimelineCursor.decode(lead, null)).isNull();
    }

    @Test
    void aCursorForAnotherLeadOrAnotherListIsRefused() {
        UUID lead = UUID.randomUUID();
        Timeline.Position position = new Timeline.Position(Instant.parse("2026-11-28T10:15:30Z"), UUID.randomUUID());
        String otherLeads = TimelineCursor.encode(UUID.randomUUID(), position);
        String inboxes = InboxCursor.encode(
                InboxCursor.CONVERSATIONS, new ConversationInbox.Position(position.occurredAt(), position.id()));
        String messages = InboxCursor.encode(
                InboxCursor.messagesOf(lead), new ConversationInbox.Position(position.occurredAt(), position.id()));

        for (String cursor : new String[] {otherLeads, inboxes, messages, "not a cursor", "bm90LWEtY3Vyc29y"}) {
            assertThatThrownBy(() -> TimelineCursor.decode(lead, cursor))
                    .as(cursor)
                    .isInstanceOfSatisfying(
                            BadRequestException.class,
                            refused -> assertThat(refused.code()).isEqualTo("invalid_cursor"));
        }
    }
}
