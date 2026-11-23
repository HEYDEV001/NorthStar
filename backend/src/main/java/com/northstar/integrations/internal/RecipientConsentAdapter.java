package com.northstar.integrations.internal;

import com.northstar.contacts.ContactConsent;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.workflow.RecipientConsent;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The action core's {@link RecipientConsent} port, answered by the contacts module (step 2.5, W32). It lives here
 * because integrations is the module above both: workflow and contacts stay independent of each other
 * (ModuleDependencyTests), and only this module knows which action types carry a message and how their payload names
 * it.
 *
 * <p>A contact who has not opted out may be messaged. An opted-out contact may get only a {@value
 * WhatsAppMessages#SEND_TYPE} whose message is a staff member's free-text reply inside the window the client opened by
 * writing after their latest opt-out (step 2.9b), with the sender's margin, as {@link ContactConsent#staffReplyAllowed}
 * decides; the database refuses anything else (actions_refuse_opted_out, NSA01).
 */
@Component
class RecipientConsentAdapter implements RecipientConsent {

    private final ContactConsent consent;

    RecipientConsentAdapter(ContactConsent consent) {
        this.consent = consent;
    }

    @Override
    public boolean mayMessage(UUID contactId, String actionType, String payloadJson) {
        if (!consent.isOptedOut(contactId)) return true;
        if (!WhatsAppMessages.SEND_TYPE.equals(actionType)) return false;
        return consent.staffReplyAllowed(
                contactId, WhatsAppSendHandler.messageId(payloadJson), WhatsAppSendHandler.WINDOW_SAFETY_MARGIN);
    }
}
