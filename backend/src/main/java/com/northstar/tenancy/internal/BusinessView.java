package com.northstar.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

/** A business as the API shows it to its own members. */
record BusinessView(UUID id, String name, String slug, String status, Instant createdAt) {

    static BusinessView of(Tenant tenant) {
        return new BusinessView(
                tenant.getId(), tenant.getName(), tenant.getSlug(), tenant.getStatus(), tenant.getCreatedAt());
    }
}
