package com.northstar.crm.internal;

import com.northstar.contacts.ManualContacts.ContactView;
import com.northstar.workflow.LeadEntry.LeadView;
import java.time.Instant;
import java.util.UUID;

/**
 * The JSON shapes of the leads API (step 2.10). Every field is always present, null when it has no value. toString
 * shows IDs and codes only, never the contact's phone, name or email.
 */
final class LeadResponses {

    private LeadResponses() {}

    /** A lead and its contact. conversationId stays null until the client writes (step 2.4b links it then). */
    record LeadResponse(
            UUID id,
            String stage,
            String origin,
            UUID workflowVersionId,
            UUID assignedMembershipId,
            UUID conversationId,
            Instant createdAt,
            ContactResponse contact) {

        static LeadResponse of(LeadView lead, ContactView contact) {
            return new LeadResponse(
                    lead.id(),
                    lead.stage(),
                    lead.origin(),
                    lead.workflowVersionId(),
                    lead.assignedMembershipId(),
                    lead.conversationId(),
                    lead.createdAt(),
                    ContactResponse.of(contact));
        }
    }

    record ContactResponse(UUID id, String phone, String name, String email, String consentState, Instant optedOutAt) {

        static ContactResponse of(ContactView contact) {
            return new ContactResponse(
                    contact.id(),
                    contact.phone(),
                    contact.name(),
                    contact.email(),
                    contact.consentState(),
                    contact.optedOutAt());
        }

        @Override
        public String toString() {
            return "ContactResponse[id=" + id + ", consentState=" + consentState + "]";
        }
    }
}
