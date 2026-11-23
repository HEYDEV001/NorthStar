package com.northstar.crm.internal;

import com.northstar.api.BadRequestException;
import com.northstar.contacts.ContactNames;
import com.northstar.contacts.PhoneNumbers;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A lead a member enters by hand (step 2.10).
 *
 * <pre>
 * {"phone": "+91 98765 43210", "name": "Asha Rao", "email": "asha@example.com", "assignedMembershipId": "..."}
 * </pre>
 *
 * Only the phone is required: a number already in E.164, or a 10-digit Indian mobile ({@link PhoneNumbers#normalize});
 * anything else, a missing phone included, is {@value #PHONE_INVALID} (P4). The name is cleaned as a WhatsApp profile
 * name is ({@link ContactNames#clean}); the email, as an invite's, must be an address of at most 254 characters, and an
 * empty one is none. The assignee is for admins: a staff member's lead is always their own. Phone numbers, names and
 * emails are never logged, so toString shows none of them.
 *
 * @param name at most {@value #MAX_NAME_INPUT} characters as sent, no U+0000
 */
record CreateLeadRequest(
        String phone,

        @Size(max = MAX_NAME_INPUT) @Pattern(regexp = NO_NUL)
        String name,

        @Email @Size(max = 254) @Pattern(regexp = NO_NUL) String email,
        UUID assignedMembershipId) {

    /** Postgres text cannot hold U+0000. */
    static final String NO_NUL = "[^\\x00]*";

    /**
     * A name longer than this as sent is refused ({@code validation_failed}); a shorter one is cleaned, which keeps at
     * most {@value ContactNames#MAX_LENGTH} code points.
     */
    static final int MAX_NAME_INPUT = 1000;

    static final String PHONE_INVALID = "phone_invalid";

    /** The phone as E.164, or 400 {@value #PHONE_INVALID}. */
    String normalizedPhone() {
        return PhoneNumbers.normalize(phone)
                .orElseThrow(() -> new BadRequestException(
                        PHONE_INVALID,
                        "A phone number is required: a number in E.164 (e.g. +919876543210) or a 10-digit Indian"
                                + " mobile"));
    }

    /** The name as kept, or null for none. */
    String cleanedName() {
        return ContactNames.clean(name);
    }

    /** The email as sent, or null for none. */
    String emailOrNull() {
        return email == null || email.isEmpty() ? null : email;
    }

    @Override
    public String toString() {
        return "CreateLeadRequest[assignedMembershipId=" + assignedMembershipId + "]";
    }
}
