package com.northstar.contacts;

/**
 * Another writer (the inbound job, or another member's manual entry) stored the same phone number between
 * {@link ManualContacts#lockExisting} finding none and {@link ManualContacts#create} (step 2.10). The transaction is
 * lost and rolls back; running the entry again finds the contact. Names nothing.
 */
public class PhoneTakenException extends RuntimeException {

    public PhoneTakenException() {
        super("The phone number was stored by another writer meanwhile");
    }
}
