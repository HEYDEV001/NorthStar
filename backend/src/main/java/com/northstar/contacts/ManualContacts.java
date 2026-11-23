package com.northstar.contacts;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The contact of a lead a member enters by hand (step 2.10, P4, W2; data-model.md section 4). Every method runs only
 * inside the caller's transaction, as an admin or a staff member, in the current tenant, with RLS: a staff member sees
 * a contact only once one of its leads or its conversation is theirs, so the caller opens the lead between
 * {@link #create} or {@link #lockExisting} and {@link #recordManualEntry}.
 *
 * <p>Phone numbers, names and emails are stored, never logged; the records here show none of them in toString.
 */
public interface ManualContacts {

    /**
     * The contact with this E.164 number, if there is one, locked for update until the transaction ends (the lock the
     * inbound job takes, so the two never decide one contact's lead at once), through the allowlisted
     * {@code northstar.contact_for_new_lead}: it finds a contact RLS still hides from a staff member.
     */
    Optional<Existing> lockExisting(String normalizedPhone);

    /**
     * A contact with this number exists. contactId is null when the caller may not learn it: a staff member, and the
     * contact has an open lead that is not theirs.
     */
    record Existing(UUID contactId, boolean hasOpenLead) {}

    /**
     * Stores a new contact with this number, name and email (consent unknown), and returns its ID. The caller found no
     * contact with {@link #lockExisting} first; a staff member's new contact stays invisible to them until their lead
     * exists, so nothing is read back here.
     *
     * @param name already cleaned ({@link ContactNames#clean}), or null
     * @param email already validated, or null
     * @throws PhoneTakenException when another writer stored the number since; the transaction must roll back
     */
    UUID create(String normalizedPhone, String name, String email);

    /**
     * After the caller's lead for the contact exists: records that the contact arrived by manual entry (one
     * {@code manual} source per contact), and fills an empty name or email with these, never replacing one.
     */
    void recordManualEntry(UUID contactId, String name, String email);

    /** The contact as the caller may see it. */
    Optional<ContactView> contact(UUID contactId);

    /** A contact: its E.164 phone, name and email (null: none), consent state, and when it first opted out. */
    record ContactView(UUID id, String phone, String name, String email, String consentState, Instant optedOutAt) {
        @Override
        public String toString() {
            return "ContactView[id=" + id + ", consentState=" + consentState + "]";
        }
    }
}
