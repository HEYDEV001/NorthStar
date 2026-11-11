package com.northstar.notifications.internal;

import com.northstar.tenancy.InviteEmailQueue;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@link InviteEmailQueue} as an action (step 1.12): one invite.email action per invite, created in the caller's
 * transaction, so its job is queued only after the invite commits. The payload holds the invite ID only, never the
 * address. The idempotency key is per invite; a resend creates a new invite, and so a new email with a new token.
 */
@Service
class InviteEmailActions implements InviteEmailQueue {

    static final String TYPE = "invite.email";

    private final Actions actions;

    InviteEmailActions(Actions actions) {
        this.actions = actions;
    }

    @Override
    public void queue(UUID inviteId) {
        actions.create(new NewAction(
                TYPE,
                "invite-email:" + inviteId,
                ActionMode.AUTOMATIC,
                false,
                "{\"inviteId\": \"" + inviteId + "\"}",
                null,
                null));
    }
}
