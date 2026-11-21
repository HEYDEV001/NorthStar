package com.northstar.workflow;

import java.util.UUID;

/**
 * The port through which the action core asks whether a contact may still be messaged (step 2.5, W32). Consent belongs
 * to the contacts module, which this module does not depend on; the module above both (integrations) implements it.
 */
public interface RecipientConsent {

    /**
     * Whether the contact has opted out, read under a shared row lock held until the caller's transaction ends, so an
     * opt-out being recorded at the same time is waited for and then seen. Runs inside the caller's transaction.
     *
     * @throws com.northstar.api.NotFoundException if the contact is not visible in the current tenant
     */
    boolean isOptedOut(UUID contactId);
}
