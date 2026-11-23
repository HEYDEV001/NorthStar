package com.northstar.tenancy.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.tenancy.RequestKeys;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RequestKeys} over {@code request_keys} (step 2.10). Native SQL with bind parameters and an explicit tenant and
 * member filter on top of RLS (D7). MANDATORY: the key is claimed in the transaction that creates its record, so a
 * request that fails leaves no key behind.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class RequestKeysService implements RequestKeys {

    private static final Pattern OPERATION = Pattern.compile("[a-z][a-z_]*\\.[a-z][a-z_]*");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    // The unique index is the conflict target: a second request with the same key waits here for the first.
    private static final String CLAIM = """
            insert into northstar.request_keys
              (tenant_id, membership_id, operation, idempotency_key, request_hash, target_id)
            values (?, ?, ?, ?, ?, ?)
            on conflict on constraint request_keys_one_per_key do nothing""";

    private static final String STORED = """
            select request_hash, target_id from northstar.request_keys
            where tenant_id = ? and membership_id = ? and operation = ? and idempotency_key = ?""";

    private final JdbcClient jdbc;
    private final TenantDirectory directory;

    RequestKeysService(JdbcClient jdbc, TenantDirectory directory) {
        this.jdbc = jdbc;
        this.directory = directory;
    }

    private record Stored(String requestHash, UUID targetId) {}

    @Override
    public Claim claim(String operation, String key, String requestHash, UUID newTargetId) {
        if (operation == null || !OPERATION.matcher(operation).matches()) {
            throw new IllegalArgumentException("An operation is a fixed code");
        }
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Not a request key");
        if (requestHash == null || !HASH.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("A request hash is SHA-256 in lowercase hex");
        }
        Objects.requireNonNull(newTargetId, "newTargetId");
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Request keys are claimed by admins and staff only");
        }
        UUID tenant = context.tenantId();
        UUID membership = directory
                .currentMembershipId()
                .orElseThrow(() -> new ForbiddenException("no_active_membership", "This account has no membership"));

        int claimed = jdbc.sql(CLAIM)
                .param(tenant)
                .param(membership)
                .param(operation)
                .param(key)
                .param(requestHash)
                .param(newTargetId)
                .update();
        if (claimed == 1) return new Claimed(newTargetId);
        Stored stored = jdbc.sql(STORED)
                .param(tenant)
                .param(membership)
                .param(operation)
                .param(key)
                .query((row, n) -> new Stored(row.getString("request_hash"), row.getObject("target_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("The request key that blocked the claim is gone"));
        return stored.requestHash().equals(requestHash) ? new Repeat(stored.targetId()) : new Reused();
    }
}
