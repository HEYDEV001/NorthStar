package com.northstar.workflow;

/**
 * Whom an action type sends something to (step 2.5). Every {@link ActionHandler} declares it, so a type that messages
 * the client cannot be created without naming the contact, and an opt-out (W32) finds and refuses every such action.
 */
public enum ActionRecipient {

    /**
     * The action sends something to a contact (a WhatsApp message to the client, for example): it names the contact as
     * {@link NewAction#recipientContactId()}, and is refused once that contact has opted out.
     */
    CONTACT,

    /**
     * The action messages nobody outside the business: a staff member, an invited user, or no one (a calendar write).
     * It names no contact, and an opt-out leaves it alone.
     */
    NONE
}
