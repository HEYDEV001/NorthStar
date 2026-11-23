package com.northstar.crm.internal;

import com.northstar.api.BadRequestException;
import com.northstar.contacts.ConversationInbox.Position;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The inbox's paging cursors (step 2.9a): opaque to clients, base64url of a version, the list it belongs to, and the
 * position of the last item of the page before (its time to the microsecond, as Postgres stores it, and its ID). A
 * messages cursor names its conversation, so it pages that conversation only. A cursor holds IDs and a time only,
 * nothing a client could not already see.
 *
 * <p>Anything this class did not issue, for this list, is 400 {@value #INVALID}; the error never repeats the cursor.
 */
final class InboxCursor {

    static final String INVALID = "invalid_cursor";

    /** The conversation list's scope. */
    static final String CONVERSATIONS = "conversations";

    private static final String VERSION = "v1";

    private static final Pattern DECODED =
            Pattern.compile("v1\\|([a-z0-9:-]{1,60})\\|(0|[1-9][0-9]{0,17})\\|([0-9a-f-]{36})");

    /** The latest time a cursor may name: the end of year 9999, well inside what Postgres's timestamptz holds. */
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private InboxCursor() {}

    /** The scope of one conversation's messages. */
    static String messagesOf(UUID conversationId) {
        return "messages:" + conversationId;
    }

    /** The cursor for the page after this position, or null when there is none. */
    static String encode(String scope, Position next) {
        if (next == null) return null;
        String text = String.join(
                "|",
                VERSION,
                scope,
                Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, next.at())),
                next.id().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    /** The position a cursor names in this scope; null (the first page) when there is no cursor. */
    static Position decode(String scope, String cursor) {
        if (cursor == null) return null;
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException notBase64) {
            throw invalid();
        }
        Matcher parts = DECODED.matcher(text);
        if (!parts.matches() || !parts.group(1).equals(scope)) throw invalid();
        Instant at = Instant.EPOCH.plus(Long.parseLong(parts.group(2)), ChronoUnit.MICROS);
        if (at.isAfter(LATEST)) throw invalid();
        UUID id;
        try {
            id = UUID.fromString(parts.group(3));
        } catch (IllegalArgumentException notUuid) {
            throw invalid();
        }
        if (!id.toString().equals(parts.group(3))) throw invalid();
        return new Position(at, id);
    }

    private static BadRequestException invalid() {
        return new BadRequestException(INVALID, "This cursor cannot be used here; start again from the first page");
    }
}
