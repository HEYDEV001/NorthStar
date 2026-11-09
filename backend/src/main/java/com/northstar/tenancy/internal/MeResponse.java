package com.northstar.tenancy.internal;

import java.util.UUID;

/**
 * GET /api/v1/me. Without an active membership, business and role are null, so the frontend knows to offer "create a
 * business" instead of showing an error.
 */
record MeResponse(UserView user, BusinessView business, String role) {

    /** The signed-in user: ID and email from the verified token, name from the profile (null until it is set). */
    record UserView(UUID id, String email, String fullName) {}
}
