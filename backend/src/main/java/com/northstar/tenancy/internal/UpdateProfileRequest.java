package com.northstar.tenancy.internal;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/v1/me/profile: replaces the signed-in user's name and phone; null (or a blank name) clears a field. Phone is
 * E.164, as data-model.md section 1 requires.
 */
record UpdateProfileRequest(
        @Size(max = 120) @Pattern(regexp = "[^\\p{Cntrl}]*") String fullName,
        @Pattern(regexp = "\\+[1-9][0-9]{6,14}") String phone) {}
