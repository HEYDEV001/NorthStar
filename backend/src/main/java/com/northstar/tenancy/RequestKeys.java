package com.northstar.tenancy;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A request's {@code Idempotency-Key} (step 2.10, P21; data-model.md section 3, {@code request_keys}): the same request
 * sent twice (a double click, two tabs, a retry) creates its record once. A key belongs to the member who sent it and
 * to one operation, so two members, or two operations, never share one.
 *
 * <p>The caller claims the key first, in the transaction that creates the record, naming the record's ID in advance.
 * A second request with the same key waits until the first commits or rolls back, then finds it: the same request
 * (same hash) is a {@link Repeat}, another one is {@link Reused}. If the first rolled back, its key was never used, and
 * the second claims it.
 *
 * <p>Runs only inside the caller's transaction, as an admin or a staff member with an active membership. The key and
 * the hash are never logged.
 */
public interface RequestKeys {

    /** The longest key, in characters. */
    int MAX_KEY_LENGTH = 100;

    /** A key: 1 to {@value #MAX_KEY_LENGTH} letters, digits, '_', '.', ':' or '-' (e.g. a UUID). */
    Pattern KEY = Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_KEY_LENGTH + "}");

    /** Manual lead entry (step 2.10): the record is the lead. */
    String LEAD_CREATE = "lead.create";

    /**
     * Claims the caller's key for this operation, with the request's hash (SHA-256, lowercase hex, of the request as
     * the caller normalises it) and the ID the record will have. Returns {@link Claimed} with that ID when the key is
     * new, {@link Repeat} with the first request's record when the same request came before, or {@link Reused} when the
     * key came with another request.
     */
    Claim claim(String operation, String key, String requestHash, UUID newTargetId);

    /** What claiming a key found. */
    sealed interface Claim {}

    /** The key is new: create the record with this ID, in this transaction. */
    record Claimed(UUID targetId) implements Claim {
        public Claimed {
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    /** The same request was made before with this key: answer with its record. */
    record Repeat(UUID targetId) implements Claim {
        public Repeat {
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    /** The key was used for another request. */
    record Reused() implements Claim {}
}
