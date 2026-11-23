package com.northstar.crm.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.api.BadRequestException;
import com.northstar.contacts.ConversationInbox.Position;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The inbox's paging cursors (step 2.9a): exact round trips, one list each, and nothing else accepted. */
class InboxCursorTests {

    private static final Position POSITION = new Position(
            Instant.parse("2026-11-22T10:15:30.123456Z"), UUID.fromString("0b7c6c1e-8f0a-4d7e-9a51-3c2f1d0e9b8a"));

    @Test
    void aCursorNamesThePositionToTheMicrosecond() {
        String cursor = InboxCursor.encode(InboxCursor.CONVERSATIONS, POSITION);

        assertThat(InboxCursor.decode(InboxCursor.CONVERSATIONS, cursor)).isEqualTo(POSITION);
        assertThat(cursor).as("URL-safe, no padding").matches("[A-Za-z0-9_-]+");
        assertThat(InboxCursor.encode(InboxCursor.CONVERSATIONS, null))
                .as("no next page")
                .isNull();
        assertThat(InboxCursor.decode(InboxCursor.CONVERSATIONS, null))
                .as("the first page")
                .isNull();
    }

    @Test
    void aCursorWorksOnlyForTheListThatIssuedIt() {
        UUID one = UUID.randomUUID();
        String messages = InboxCursor.encode(InboxCursor.messagesOf(one), POSITION);

        assertThat(InboxCursor.decode(InboxCursor.messagesOf(one), messages)).isEqualTo(POSITION);
        assertInvalid(InboxCursor.messagesOf(UUID.randomUUID()), messages);
        assertInvalid(InboxCursor.CONVERSATIONS, messages);
        assertInvalid(InboxCursor.messagesOf(one), InboxCursor.encode(InboxCursor.CONVERSATIONS, POSITION));
    }

    @Test
    void anythingElseIsInvalid() {
        for (String text : List.of(
                "v2|conversations|1|" + POSITION.id(),
                "v1|conversations|-1|" + POSITION.id(),
                "v1|conversations|01|" + POSITION.id(),
                "v1|conversations|1|" + POSITION.id().toString().toUpperCase(),
                "v1|conversations|1|not-a-uuid-not-a-uuid-not-a-uuid-xxxx",
                "v1|conversations|1",
                "v1|conversations|1|" + POSITION.id() + "|extra",
                "v1|conversations|999999999999999999|" + POSITION.id())) {
            assertInvalid(InboxCursor.CONVERSATIONS, encoded(text));
        }
        assertInvalid(InboxCursor.CONVERSATIONS, "not base64 at all!");
        assertInvalid(InboxCursor.CONVERSATIONS, "");
    }

    private static void assertInvalid(String scope, String cursor) {
        assertThatThrownBy(() -> InboxCursor.decode(scope, cursor))
                .as(cursor)
                .isInstanceOfSatisfying(BadRequestException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("invalid_cursor");
                    assertThat(refused.getMessage()).doesNotContain(cursor.isEmpty() ? "\u0000" : cursor);
                });
    }

    private static String encoded(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }
}
