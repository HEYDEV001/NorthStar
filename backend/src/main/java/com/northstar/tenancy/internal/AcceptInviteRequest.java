package com.northstar.tenancy.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** POST /api/v1/invites/accept: the raw token from the invite link. */
record AcceptInviteRequest(
        @NotNull @Pattern(regexp = InviteTokens.FORMAT) String token) {}
