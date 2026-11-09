package com.northstar.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The record does not exist for this caller. Another tenant's record is not visible inside the tenant scope (RLS and
 * @TenantId), so it is reported exactly like a missing one and its existence is never revealed (P9). The response is
 * always the same; the record type and requested ID go only to the log, so attempts can be traced.
 *
 * <p>The ID is a UUID on purpose: path IDs are typed UUIDs, so a malformed ID is a 400 before anything is looked up,
 * and no free text from the client ever reaches the log.
 */
public class NotFoundException extends ApiException {

    private final String recordType;
    private final UUID requestedId;

    /**
     * @param recordType a fixed name chosen in code, e.g. "membership", never client input
     * @param requestedId the ID the caller asked for
     */
    public NotFoundException(String recordType, UUID requestedId) {
        super(HttpStatus.NOT_FOUND, "not_found", "Not found", Map.of());
        this.recordType = recordType;
        this.requestedId = requestedId;
    }

    public String recordType() {
        return recordType;
    }

    public UUID requestedId() {
        return requestedId;
    }
}
