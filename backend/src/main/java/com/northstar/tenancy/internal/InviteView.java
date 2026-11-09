package com.northstar.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

/** An invite as an admin sees it. Never the token, its hash or a link. */
record InviteView(
        UUID id, String email, String role, String status, UUID invitedBy, Instant createdAt, Instant expiresAt) {

    static InviteView of(Invite invite, String status) {
        return new InviteView(
                invite.getId(),
                invite.getEmail(),
                invite.getRole().databaseValue(),
                status,
                invite.getInvitedBy(),
                invite.getCreatedAt(),
                invite.getExpiresAt());
    }
}
