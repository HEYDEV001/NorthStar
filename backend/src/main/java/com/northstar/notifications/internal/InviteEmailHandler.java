package com.northstar.notifications.internal;

import com.northstar.integrations.EmailSender;
import com.northstar.tenancy.InviteDelivery;
import com.northstar.tenancy.InviteDelivery.InviteToSend;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.RetryPolicy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the invite email (step 1.12), as the worker's system actor in the invite's tenant:
 *
 * <ol>
 *   <li>loads the invite; one that is no longer pending (revoked, accepted, expired) gets nothing, and the action
 *       succeeds with {"sent": false};
 *   <li>derives this action's token and sets its hash on the invite through the allowlisted function;
 *   <li>sends the email under the action's idempotency key.
 * </ol>
 *
 * <p>Retries follow WhatsApp's schedule (workflow-spec.md section 10). Logs carry action and invite IDs only.
 */
@Component
class InviteEmailHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(InviteEmailHandler.class);
    private static final String NOT_SENT = "{\"sent\": false}";

    private final InviteDelivery invites;
    private final InviteTokenDerivation tokens;
    private final EmailSender email;
    private final JsonMapper json;
    private final String appBaseUrl;

    InviteEmailHandler(
            InviteDelivery invites,
            InviteTokenDerivation tokens,
            EmailSender email,
            JsonMapper json,
            @Value("${northstar.app.base-url}") String appBaseUrl) {
        this.invites = invites;
        this.tokens = tokens;
        this.email = email;
        this.json = json;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public String type() {
        return InviteEmailActions.TYPE;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.STANDARD_BACKOFF;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(15);
    }

    @Override
    public ActionOutcome execute(ActionRequest request) {
        UUID inviteId = UUID.fromString(
                json.readTree(request.payloadJson()).get("inviteId").asString());
        Optional<InviteToSend> invite = invites.pendingInvite(inviteId);
        if (invite.isEmpty()) {
            log.info("invite_email.not_pending action_id={} invite_id={}", request.actionId(), inviteId);
            return ActionOutcome.succeeded(NOT_SENT);
        }
        String token = tokens.tokenFor(request.actionId());
        if (!invites.setToken(inviteId, token)) {
            log.info("invite_email.not_pending action_id={} invite_id={}", request.actionId(), inviteId);
            return ActionOutcome.succeeded(NOT_SENT);
        }
        String link = appBaseUrl + "/invite#token=" + token;
        ActionOutcome outcome = email.send(InviteEmailMessage.of(invite.get(), link), request.idempotencyKey());
        log.info(
                "invite_email.attempt action_id={} invite_id={} outcome={}",
                request.actionId(),
                inviteId,
                outcome instanceof ActionOutcome.Succeeded ? "sent" : "failed");
        return outcome;
    }
}
