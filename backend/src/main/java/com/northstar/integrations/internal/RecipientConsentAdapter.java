package com.northstar.integrations.internal;

import com.northstar.contacts.ContactConsent;
import com.northstar.workflow.RecipientConsent;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The action core's {@link RecipientConsent} port, answered by the contacts module (step 2.5, W32). It lives here
 * because integrations is the module above both: workflow and contacts stay independent of each other
 * (ModuleDependencyTests), and this adapter only passes the question on.
 */
@Component
class RecipientConsentAdapter implements RecipientConsent {

    private final ContactConsent consent;

    RecipientConsentAdapter(ContactConsent consent) {
        this.consent = consent;
    }

    @Override
    public boolean isOptedOut(UUID contactId) {
        return consent.isOptedOut(contactId);
    }
}
