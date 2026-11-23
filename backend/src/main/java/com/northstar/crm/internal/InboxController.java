package com.northstar.crm.internal;

import com.northstar.api.NotFoundException;
import com.northstar.contacts.ConversationInbox;
import com.northstar.contacts.ConversationInbox.ConversationSummary;
import com.northstar.contacts.ConversationInbox.MessageItem;
import com.northstar.contacts.ConversationInbox.Page;
import com.northstar.crm.internal.InboxResponses.ConversationResponse;
import com.northstar.crm.internal.InboxResponses.MessageResponse;
import com.northstar.crm.internal.InboxResponses.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbox's reads (step 2.9a, build-document.md section 3, Conversations): an admin sees every conversation of the
 * business, a staff member only their assigned ones (P11); another tenant's conversation, another member's and a
 * missing one are the same 404 (P9). Lists are newest first and paged by an opaque cursor: {@code limit} 1 to
 * {@value ConversationInbox#MAX_PAGE_SIZE} (50 by default), {@code cursor} the previous page's {@code nextCursor}.
 */
@RestController
class InboxController {

    private static final String DEFAULT_LIMIT = "50";

    private final ConversationInbox inbox;

    InboxController(ConversationInbox inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/conversations")
    PageResponse<ConversationResponse> conversations(
            @RequestParam(required = false) @Size(min = 1, max = 200) String cursor,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(ConversationInbox.MAX_PAGE_SIZE) int limit) {
        Page<ConversationSummary> page =
                inbox.conversations(InboxCursor.decode(InboxCursor.CONVERSATIONS, cursor), limit);
        return new PageResponse<>(
                page.items().stream().map(ConversationResponse::of).toList(),
                InboxCursor.encode(InboxCursor.CONVERSATIONS, page.next()));
    }

    @GetMapping("/conversations/{conversationId}")
    ConversationResponse conversation(@PathVariable UUID conversationId) {
        return inbox.conversation(conversationId)
                .map(ConversationResponse::of)
                .orElseThrow(() -> new NotFoundException("conversation", conversationId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    PageResponse<MessageResponse> messages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) @Size(min = 1, max = 200) String cursor,
            @RequestParam(defaultValue = DEFAULT_LIMIT) @Min(1) @Max(ConversationInbox.MAX_PAGE_SIZE) int limit) {
        String scope = InboxCursor.messagesOf(conversationId);
        Page<MessageItem> page = inbox.messages(conversationId, InboxCursor.decode(scope, cursor), limit);
        return new PageResponse<>(
                page.items().stream().map(MessageResponse::of).toList(), InboxCursor.encode(scope, page.next()));
    }
}
