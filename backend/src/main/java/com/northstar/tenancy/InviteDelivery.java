package com.northstar.tenancy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the invite email needs from tenancy (step 1.12), for the worker acting as the system in the invite's tenant.
 * Nothing here returns a token or its hash.
 */
public interface InviteDelivery {

    /** A pending invite, ready to send: who to, which role, which business, and when it expires. */
    record InviteToSend(
            UUID inviteId, String email, Role role, String businessName, String timezone, Instant expiresAt) {

        @Override
        public String toString() {
            return "InviteToSend[" + inviteId + "]"; // never the address in a log line
        }
    }

    /** The invite if it is still pending (not accepted, revoked or expired), else empty. */
    Optional<InviteToSend> pendingInvite(UUID inviteId);

    /**
     * Makes this raw token the invite's token, if the invite is still pending, through the allowlisted
     * northstar.set_invite_token(). Only its hash reaches the database. False when the invite is no longer pending or the
     * caller is not the system actor.
     */
    boolean setToken(UUID inviteId, String rawToken);
}
