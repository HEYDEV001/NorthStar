package com.northstar.tenancy.internal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/v1/invites. */
record CreateInviteRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @Pattern(regexp = "admin|staff") String role) {}
