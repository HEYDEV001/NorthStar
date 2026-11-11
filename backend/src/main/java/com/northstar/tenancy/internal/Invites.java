package com.northstar.tenancy.internal;

import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.tenancy.InviteEmailQueue;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff invites, admin side (step 1.9, build-document.md section 3): create, list, revoke, resend. All admin-only and
 * inside the admin's tenant scope; another tenant's invite is the same 404 as a missing one (P9). Create and resend
 * queue the invite email in the same transaction (step 1.12); the link exists only in that email.
 */
@Service
class Invites {

    private static final String ONE_PENDING_PER_EMAIL = "invites_one_pending_per_email";

    private final InviteRepository invites;
    private final MembershipRepository memberships;
    private final AuditLogRepository auditLog;
    private final InviteEmailQueue emails;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    Invites(
            InviteRepository invites,
            MembershipRepository memberships,
            AuditLogRepository auditLog,
            InviteEmailQueue emails,
            PlatformTransactionManager transactionManager) {
        this.invites = invites;
        this.memberships = memberships;
        this.auditLog = auditLog;
        this.emails = emails;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    InviteView create(CreateInviteRequest request) {
        TenantContext admin = requireAdmin();
        String email = request.email().trim();
        try {
            return transactions.execute(status -> {
                invites.findOpenForEmail(email).ifPresent(open -> {
                    throw invitePending(open.getId());
                });
                return issue(admin, email, Role.fromDatabaseValue(request.role()), "invite.created");
            });
        } catch (RuntimeException failure) {
            if (!ONE_PENDING_PER_EMAIL.equals(DatabaseErrors.constraintName(failure))) throw failure;
            // Another admin invited the same email a moment earlier.
            UUID open = readOnly.execute(
                    status -> invites.findOpenForEmail(email).map(Invite::getId).orElse(null));
            throw invitePending(open);
        }
    }

    List<InviteView> list() {
        requireAdmin();
        Instant now = Instant.now();
        return readOnly.execute(status -> invites.findByAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtAsc().stream()
                .map(invite -> InviteView.of(invite, invite.openStatus(now)))
                .toList());
    }

    /** Revoking twice is a 200 no-op; an accepted invite cannot be revoked. */
    InviteView revoke(UUID inviteId) {
        TenantContext admin = requireAdmin();
        return transactions.execute(status -> {
            Invite invite = lockedOrNotFound(inviteId);
            if (invite.isRevoked()) return InviteView.of(invite, "revoked");
            if (invite.isAccepted()) throw alreadyAccepted();
            Instant now = Instant.now();
            String from = invite.openStatus(now);
            invite.revoke(now);
            invites.saveAndFlush(invite);
            auditLog.save(AuditLogEntry.change(admin.userId(), "invite.revoked", "invites", inviteId, from, "revoked"));
            return InviteView.of(invite, "revoked");
        });
    }

    /**
     * A new token and expiry for a pending or expired invite: the old invite is locked and revoked first, then the new
     * one is inserted in the same transaction, so the one-pending-per-email index never fires. The old link stops
     * working.
     */
    InviteView resend(UUID inviteId) {
        TenantContext admin = requireAdmin();
        return transactions.execute(status -> {
            Invite old = lockedOrNotFound(inviteId);
            if (old.isAccepted()) throw alreadyAccepted();
            if (old.isRevoked())
                throw new ConflictException("invite_already_revoked", "This invite has been withdrawn");
            old.revoke(Instant.now());
            invites.saveAndFlush(old);
            return issue(admin, old.getEmail(), old.getRole(), "invite.resent");
        });
    }

    /**
     * Creates an invite and queues its email in the same transaction. token_hash starts as the hash of a throwaway
     * token that is never sent, so no link works until the email action sets the real one. Audited with IDs only.
     */
    private InviteView issue(TenantContext admin, String email, Role role, String action) {
        String throwaway = InviteTokens.newToken();
        Invite invite =
                invites.saveAndFlush(new Invite(email, role, InviteTokens.hash(throwaway), currentMembership(admin)));
        auditLog.save(new AuditLogEntry(admin.userId(), action, "invites", invite.getId()));
        emails.queue(invite.getId());
        return InviteView.of(invite, invite.openStatus(Instant.now()));
    }

    private UUID currentMembership(TenantContext admin) {
        return memberships.findByUserId(admin.userId()).stream()
                .filter(membership -> Members.ACTIVE.equals(membership.getStatus()))
                .findFirst()
                .map(Membership::getId)
                .orElseThrow(() -> new IllegalStateException("The admin has no active membership in this tenant"));
    }

    private Invite lockedOrNotFound(UUID inviteId) {
        return invites.findLockedById(inviteId).orElseThrow(() -> new NotFoundException("invite", inviteId));
    }

    private static TenantContext requireAdmin() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN) throw new ForbiddenException("admin_only", "Only an admin can do this");
        return context;
    }

    private static ConflictException invitePending(UUID openInvite) {
        return new ConflictException(
                "invite_pending",
                "There is already a pending invite for this email; resend it instead",
                openInvite == null ? Map.of() : Map.of("inviteId", openInvite));
    }

    private static ConflictException alreadyAccepted() {
        return new ConflictException("invite_already_accepted", "This invite has already been accepted");
    }
}
