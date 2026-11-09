package com.northstar.tenancy.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** PATCH /api/v1/members/{membershipId}/role. */
record ChangeRoleRequest(
        @NotNull @Pattern(regexp = "admin|staff") String role) {}
