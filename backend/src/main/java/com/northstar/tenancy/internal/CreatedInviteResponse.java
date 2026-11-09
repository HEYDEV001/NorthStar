package com.northstar.tenancy.internal;

/**
 * The answer to create and resend: the invite and its link, returned this once so an admin can share it until the
 * invite email exists (step 1.12). The link is never listed, logged or audited.
 */
record CreatedInviteResponse(InviteView invite, String inviteLink) {}
