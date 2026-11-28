package com.northstar.crm.internal;

import com.northstar.contacts.ManualContacts;
import com.northstar.crm.internal.LeadResponses.LeadResponse;
import com.northstar.workflow.LeadEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A lead and its contact as the leads API answers with them (steps 2.10 and 3.3b), as the caller may see them: an
 * admin any lead of the business, a staff member only their own (RLS). Runs inside the caller's transaction.
 */
@Service
class LeadViews {

    private final LeadEntry leads;
    private final ManualContacts contacts;

    LeadViews(LeadEntry leads, ManualContacts contacts) {
        this.leads = leads;
        this.contacts = contacts;
    }

    /** The lead and its contact, or empty for one the caller cannot see, a deleted one or none. */
    Optional<LeadResponse> lead(UUID leadId) {
        return leads.lead(leadId)
                .flatMap(lead -> contacts.contact(lead.contactId()).map(contact -> LeadResponse.of(lead, contact)));
    }
}
