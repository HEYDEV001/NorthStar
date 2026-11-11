package com.northstar.tenancy.internal;

import com.northstar.tenancy.InviteDelivery;
import com.northstar.tenancy.TenantScope;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link InviteDelivery}: reads run under RLS in the scope's tenant; the token goes through the allowlisted function. */
@Service
class InviteDeliveryService implements InviteDelivery {

    private final InviteRepository invites;
    private final TenantRepository tenants;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    InviteDeliveryService(
            InviteRepository invites, TenantRepository tenants, PlatformTransactionManager transactionManager) {
        this.invites = invites;
        this.tenants = tenants;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<InviteToSend> pendingInvite(UUID inviteId) {
        UUID tenantId = TenantScope.require().tenantId();
        return readOnly.execute(status -> invites.findById(inviteId)
                .filter(invite -> "pending".equals(invite.openStatus(Instant.now()))
                        && !invite.isAccepted()
                        && !invite.isRevoked())
                .flatMap(invite -> tenants.findById(tenantId)
                        .map(tenant -> new InviteToSend(
                                invite.getId(),
                                invite.getEmail(),
                                invite.getRole(),
                                tenant.getName(),
                                tenant.getTimezone(),
                                invite.getExpiresAt()))));
    }

    @Override
    public boolean setToken(UUID inviteId, String rawToken) {
        if (rawToken == null || !rawToken.matches(InviteTokens.FORMAT)) {
            throw new IllegalArgumentException("Not an invite token");
        }
        TenantScope.require();
        String hash = InviteTokens.hash(rawToken);
        return Boolean.TRUE.equals(transactions.execute(status -> invites.setToken(inviteId, hash)));
    }
}
