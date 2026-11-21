package com.northstar.integrations.internal;

import com.northstar.contacts.MessageToSend;
import com.northstar.contacts.OutboundMessages;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRecipient;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.ClosedAction;
import com.northstar.workflow.FailureClass;
import com.northstar.workflow.RetryPolicy;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one queued WhatsApp message (step 2.6a, integration-specs.md section 2), as the worker's system actor in the
 * message's tenant:
 *
 * <ol>
 *   <li>reads the message, its conversation's window and number, the contact's number, the template and the
 *       integration, when the send runs (never when it was queued). Meta's ID already stored: the send happened, and it
 *       succeeds without calling Meta again (P1). A text message outside the window fails as
 *       {@value #WINDOW_CLOSED_NO_TEMPLATE} (W33); a template that is not approved fails as
 *       {@value #TEMPLATE_NOT_APPROVED} (W34); a business whose WhatsApp is not connected pauses it as auth
 *       {@value #INTEGRATION_NOT_CONNECTED}, without calling Meta;
 *   <li>decrypts the business token through the worker's credential store, and calls Meta through the
 *       {@link WhatsAppSender} outside any transaction;
 *   <li>stores Meta's message ID at once, in its own transaction, then the integration's health. An auth failure sets
 *       the integration to error, so the business's other sends pause before calling Meta.
 * </ol>
 *
 * <p>When a send closes without succeeding (failed, or cancelled because its contact opted out), {@link #closed} marks
 * its message failed with the action's code, in the transaction that closes it. Logs carry action and message IDs,
 * attempts, classes and fixed codes only.
 */
@Component
class WhatsAppSendHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSendHandler.class);

    /** The window counts as closed this long before it ends: clock differences and the send's own time. */
    static final Duration WINDOW_SAFETY_MARGIN = Duration.ofMinutes(2);

    /**
     * Well under the 5-minute lease. The adapter's HTTP client must give up well before it (step 2.6b), so an answer
     * from Meta always arrives in time to be stored rather than being cut off and retried as a duplicate.
     */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    static final String MESSAGE_NOT_FOUND = "message_not_found";
    static final String MESSAGE_NOT_QUEUED = "message_not_queued";
    static final String MESSAGE_TYPE_UNSUPPORTED = "message_type_unsupported";
    static final String WINDOW_CLOSED_NO_TEMPLATE = "window_closed_no_template";
    static final String TEMPLATE_NOT_APPROVED = "template_not_approved";
    static final String INTEGRATION_NOT_CONNECTED = "integration_not_connected";
    static final String CREDENTIAL_UNREADABLE = "credential_unreadable";

    private final OutboundMessages messages;
    private final WhatsAppConnection connection;
    private final ObjectProvider<CredentialStore> credentials;
    private final ObjectProvider<WhatsAppSender> senders;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    /**
     * The credential store exists only on the worker, and the Cloud API sender only from step 2.6b, but every process
     * needs this handler: creating a send reads its {@link #recipient()}. Only the worker ever runs it.
     */
    WhatsAppSendHandler(
            OutboundMessages messages,
            WhatsAppConnection connection,
            ObjectProvider<CredentialStore> credentials,
            ObjectProvider<WhatsAppSender> senders,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.messages = messages;
        this.connection = connection;
        this.credentials = credentials;
        this.senders = senders;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** The action's payload: the message ID only, never the text (step 2.3). */
    static String payloadFor(UUID messageId) {
        return "{\"messageId\": \"" + messageId + "\"}";
    }

    @Override
    public String type() {
        return WhatsAppMessages.SEND_TYPE;
    }

    /** Every send messages the conversation's contact, so an opt-out refuses it (W32). */
    @Override
    public ActionRecipient recipient() {
        return ActionRecipient.CONTACT;
    }

    /** WhatsApp send (workflow-spec.md section 10): 6 attempts over 81.5 minutes. */
    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.STANDARD_BACKOFF;
    }

    @Override
    public Duration timeout() {
        return TIMEOUT;
    }

    /** What the read decided: an outcome without calling Meta, or a message to send. */
    private sealed interface Step {}

    private record Done(ActionOutcome outcome) implements Step {}

    private record Send(WhatsAppSender.OutgoingWhatsApp message, UUID credentialId) implements Step {}

    @Override
    public ActionOutcome execute(ActionRequest request) {
        UUID messageId = messageId(request.payloadJson());
        Step step = readOnly.execute(status -> prepare(request, messageId));
        return switch (step) {
            case Done done -> done.outcome();
            case Send send -> send(request, send);
        };
    }

    /**
     * Marks the message failed with the action's code (step 2.6a): {@code contact_opted_out} for a cancelled send, the
     * last error code for a failed one. A message Meta has accepted is left as it is.
     */
    @Override
    public void closed(ClosedAction action) {
        UUID messageId = messageId(action.payloadJson());
        boolean failed = messages.markFailed(messageId, action.code());
        log.info(
                "whatsapp.send.closed action_id={} message_id={} closing={} code={} message_failed={}",
                action.actionId(),
                messageId,
                action.closing().name().toLowerCase(Locale.ROOT),
                action.code(),
                failed);
    }

    private Step prepare(ActionRequest request, UUID messageId) {
        Optional<MessageToSend> found = messages.toSend(messageId, WINDOW_SAFETY_MARGIN);
        if (found.isEmpty()) return refused(request, messageId, MESSAGE_NOT_FOUND); // another tenant's too (P9)
        MessageToSend message = found.get();
        if (message.accepted()) {
            log.info(
                    "whatsapp.send.already_sent action_id={} message_id={} attempt={}",
                    request.actionId(),
                    messageId,
                    request.attempt());
            return new Done(ActionOutcome.succeeded(result(messageId, "already_sent")));
        }
        if (!message.isQueued()) return refused(request, messageId, MESSAGE_NOT_QUEUED);

        WhatsAppSender.Body body;
        switch (message.type()) {
            case "text" -> {
                if (!message.windowOpen()) return refused(request, messageId, WINDOW_CLOSED_NO_TEMPLATE);
                body = new WhatsAppSender.Text(message.text());
            }
            case "template" -> {
                MessageToSend.Template template = message.template();
                if (!"approved".equals(template.status())) {
                    return refused(request, messageId, TEMPLATE_NOT_APPROVED);
                }
                body = new WhatsAppSender.Template(template.name(), template.language(), template.parameters());
            }
            default -> {
                return refused(request, messageId, MESSAGE_TYPE_UNSUPPORTED);
            }
        }

        Optional<WhatsAppConnection.Current> current = connection.current();
        if (current.isEmpty() || !current.get().usable()) {
            log.warn(
                    "whatsapp.send.paused action_id={} message_id={} attempt={} code={}",
                    request.actionId(),
                    messageId,
                    request.attempt(),
                    INTEGRATION_NOT_CONNECTED);
            return new Done(ActionOutcome.auth(INTEGRATION_NOT_CONNECTED));
        }
        return new Send(
                new WhatsAppSender.OutgoingWhatsApp(
                        messageId, message.whatsAppPhoneNumberId(), message.recipientPhone(), body),
                current.get().credentialId());
    }

    private ActionOutcome send(ActionRequest request, Send send) {
        UUID messageId = send.message().messageId();
        CredentialStore store = credentials.getIfAvailable();
        if (store == null) throw new IllegalStateException("Business tokens are read only on the worker");
        WhatsAppSender sender = senders.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("No WhatsApp sender is configured; the Cloud API adapter is step 2.6b");
        }

        Optional<Secret> token;
        try {
            token = store.read(send.credentialId());
        } catch (CredentialDecryptionException e) {
            // Its message names the credential and key IDs only; still, only the code is logged.
            transactions.executeWithoutResult(status -> connection.recordFailure(CREDENTIAL_UNREADABLE, true));
            log.warn(
                    "whatsapp.send.paused action_id={} message_id={} attempt={} code={}",
                    request.actionId(),
                    messageId,
                    request.attempt(),
                    CREDENTIAL_UNREADABLE);
            return ActionOutcome.auth(CREDENTIAL_UNREADABLE);
        }
        if (token.isEmpty()) {
            log.warn(
                    "whatsapp.send.paused action_id={} message_id={} attempt={} code={}",
                    request.actionId(),
                    messageId,
                    request.attempt(),
                    INTEGRATION_NOT_CONNECTED);
            return ActionOutcome.auth(INTEGRATION_NOT_CONNECTED);
        }

        WhatsAppSender.WhatsAppSendResult result;
        try (Secret secret = token.get()) {
            result = sender.send(send.message(), secret);
        }

        return switch (result) {
            case WhatsAppSender.Accepted accepted -> {
                // The ID first, in its own transaction, so nothing after it can lose it; a re-run then finds it (P1).
                boolean recorded = Boolean.TRUE.equals(
                        transactions.execute(status -> messages.markSent(messageId, accepted.providerMessageId())));
                transactions.executeWithoutResult(status -> connection.recordSuccess());
                log.info(
                        "whatsapp.send.sent action_id={} message_id={} attempt={} kind={} recorded={}",
                        request.actionId(),
                        messageId,
                        request.attempt(),
                        send.message().body().kind(),
                        recorded);
                yield ActionOutcome.succeeded(result(messageId, "sent"));
            }
            case WhatsAppSender.Rejected rejected -> {
                ActionOutcome.Failed failure = rejected.failure();
                boolean auth = failure.failureClass() == FailureClass.AUTH;
                transactions.executeWithoutResult(status -> connection.recordFailure(failure.code(), auth));
                log.warn(
                        "whatsapp.send.failed action_id={} message_id={} attempt={} class={} code={}",
                        request.actionId(),
                        messageId,
                        request.attempt(),
                        failure.failureClass().databaseValue(),
                        failure.code());
                yield failure;
            }
        };
    }

    /** A send that can never succeed as it stands: permanent, without calling Meta. */
    private Done refused(ActionRequest request, UUID messageId, String code) {
        log.info(
                "whatsapp.send.refused action_id={} message_id={} attempt={} code={}",
                request.actionId(),
                messageId,
                request.attempt(),
                code);
        return new Done(ActionOutcome.permanent(code));
    }

    private UUID messageId(String payloadJson) {
        return UUID.fromString(json.readTree(payloadJson).get("messageId").asString());
    }

    private static String result(UUID messageId, String outcome) {
        return "{\"messageId\": \"" + messageId + "\", \"outcome\": \"" + outcome + "\"}";
    }
}
