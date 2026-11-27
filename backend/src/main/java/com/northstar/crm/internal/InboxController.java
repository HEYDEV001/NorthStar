package com.northstar.crm.internal;

import com.northstar.api.NotFoundException;
import com.northstar.contacts.ConversationInbox;
import com.northstar.contacts.ConversationInbox.ConversationSummary;
import com.northstar.contacts.ConversationInbox.MessageItem;
import com.northstar.contacts.ConversationInbox.Page;
import com.northstar.crm.internal.InboxResponses.ConversationResponse;
import com.northstar.crm.internal.InboxResponses.MessageResponse;
import com.northstar.crm.internal.InboxResponses.PageResponse;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.tenancy.IdempotencyKeyHeader;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The inbox (steps 2.9a and 2.9b, build-document.md section 3, Conversations): an admin sees and replies to every
 * conversation of the business, a staff member only their assigned ones (P11); another tenant's conversation, another
 * member's and a missing one are the same 404 (P9). Lists are newest first and paged by an opaque cursor: {@code limit}
 * 1 to {@value ConversationInbox#MAX_PAGE_SIZE} (50 by default), {@code cursor} the previous page's
 * {@code nextCursor}.
 *
 * <p>A reply needs an {@value IdempotencyKeyHeader#NAME} header (P21): 201 with the queued message the first time,
 * 200 with the same message when the same request comes again, 422 {@code idempotency_key_reused} when the key comes
 * with another reply. The send runs later, on the worker, and checks the window again then (W33).
 *
 * <p>Take over and hand back (step 2.9c) answer 200 with the conversation as it is afterwards; doing either again
 * changes nothing and answers the same.
 */
@RestController
class InboxController {

    private static final String DEFAULT_LIMIT = "50";

    private final ConversationInbox inbox;
    private final WhatsAppMessages whatsApp;
    private final ConversationTakeover takeover;

    InboxController(ConversationInbox inbox, WhatsAppMessages whatsApp, ConversationTakeover takeover) {
        this.inbox = inbox;
        this.whatsApp = whatsApp;
        this.takeover = takeover;
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

    @PostMapping("/conversations/{conversationId}/messages")
    ResponseEntity<MessageResponse> reply(
            @PathVariable UUID conversationId,
            @RequestHeader(name = IdempotencyKeyHeader.NAME, required = false) String idempotencyKey,
            @Valid @RequestBody StaffReplyRequest request) {
        String key = IdempotencyKeyHeader.require(idempotencyKey, "A reply");
        WhatsAppMessages.Queued queued = whatsApp.queueStaffReply(conversationId, request.toContent(), key);
        MessageResponse message = inbox.message(conversationId, queued.messageId())
                .map(MessageResponse::of)
                .orElseThrow(() -> new NotFoundException("message", queued.messageId()));
        return ResponseEntity.status(queued.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(message);
    }

    @PostMapping("/conversations/{conversationId}/take-over")
    ConversationResponse takeOver(@PathVariable UUID conversationId) {
        takeover.takeOver(conversationId);
        return conversation(conversationId);
    }

    @PostMapping("/conversations/{conversationId}/hand-back")
    ConversationResponse handBack(@PathVariable UUID conversationId) {
        takeover.handBack(conversationId);
        return conversation(conversationId);
    }
}
