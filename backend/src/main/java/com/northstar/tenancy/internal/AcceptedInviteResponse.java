package com.northstar.tenancy.internal;

/** The business the user just joined, and their role in it. */
record AcceptedInviteResponse(BusinessView business, String role) {}
