package com.northstar.tenancy.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/businesses. The slug is optional: without one, it is generated from the name. Nothing else is accepted;
 * unknown fields are a 400.
 */
record CreateBusinessRequest(
        @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = "[^\\p{Cntrl}]*")
        String name,

        @Size(min = Slugs.MIN_LENGTH, max = Slugs.MAX_LENGTH) @Pattern(regexp = Slugs.PATTERN)
        String slug) {}
