package com.northstar.notifications.internal;

import com.northstar.integrations.OutgoingEmail;
import com.northstar.tenancy.InviteDelivery.InviteToSend;
import com.northstar.tenancy.Role;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The invite email's wording (confirmed for step 1.12), plain text. The expiry is shown in the business's timezone, as
 * "18 November 2026, 10:35 PM IST".
 */
final class InviteEmailMessage {

    static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a z", Locale.ENGLISH);

    private InviteEmailMessage() {}

    static OutgoingEmail of(InviteToSend invite, String link) {
        String date = EXPIRY.format(invite.expiresAt().atZone(ZoneId.of(invite.timezone())));
        String subject = invite.businessName() + " invited you to Northstar";
        String text = "Hi,\n"
                + "\n"
                + invite.businessName() + " has invited you to join their team on Northstar as " + role(invite.role())
                + ".\n"
                + "\n"
                + "Accept your invite: " + link + "\n"
                + "\n"
                + "This link works once and expires on " + date + ".\n"
                + "If you weren't expecting this, you can ignore this email.\n"
                + "\n"
                + "— Northstar\n";
        return new OutgoingEmail(invite.email(), subject, text);
    }

    private static String role(Role role) {
        return switch (role) {
            case ADMIN -> "Admin";
            case STAFF -> "Staff";
            case SYSTEM -> throw new IllegalArgumentException("Invites are for members");
        };
    }
}
