package com.northstar.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

/** A member as an admin sees them in the team list. No phone numbers. */
record MemberView(UUID membershipId, UUID userId, String fullName, String role, String status, Instant createdAt) {

    static MemberView of(Membership member, String fullName) {
        return new MemberView(
                member.getId(),
                member.getUserId(),
                fullName,
                member.getRole().databaseValue(),
                member.getStatus(),
                member.getCreatedAt());
    }
}
