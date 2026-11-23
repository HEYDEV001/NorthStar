package com.northstar.workflow;

import java.util.UUID;

/**
 * The port through which the action core asks whether an action may still message its recipient (step 2.5, W32).
 * Consent belongs to the contacts module, which this module does not depend on; the module above both (integrations)
 * implements it, and knows the action types and payloads that carry a message.
 */
public interface RecipientConsent {

    /**
     * Whether this action may message this contact: true when the contact has not opted out, and otherwise only for
     * the one exception step 2.9b allows (a staff member's free-text reply after the client wrote again, inside the
     * window that message opened, with the sender's margin). Consent is read under the contact's shared row lock, held
     * until the caller's transaction ends, so an opt-out being recorded at the same time is waited for and then seen.
     * Runs inside the caller's transaction.
     *
     * @throws com.northstar.api.NotFoundException if the contact is not visible in the current tenant
     */
    boolean mayMessage(UUID contactId, String actionType, String payloadJson);
}
