package com.northstar.tenancy.internal;

import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The business's team (step 1.8, build-document.md section 3: admins invite, change roles and deactivate; staff have no
 * team access). Every action runs as the request's member inside their tenant scope. The role is checked first, so
 * staff get 403 whatever ID they send; an ID from another tenant, or none at all, is the same 404 (P9). Changes are
 * idempotent: repeating one is a 200 with no new audit entry. A business always keeps an active admin (P14), enforced
 * by the database trigger northstar.keep_an_active_admin().
 */
@Service
class Members {

    static final String ACTIVE = "active";
    static final String DEACTIVATED = "deactivated";

    private final MembershipRepository memberships;
    private final UserProfileRepository profiles;
    private final AuditLogRepository auditLog;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    Members(
            MembershipRepository memberships,
            UserProfileRepository profiles,
            AuditLogRepository auditLog,
            PlatformTransactionManager transactionManager) {
        this.memberships = memberships;
        this.profiles = profiles;
        this.auditLog = auditLog;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    List<MemberView> list() {
        requireAdmin();
        return readOnly.execute(status -> {
            List<Membership> team = memberships.findAll();
            Map<UUID, String> names = profiles
                    .findAllById(team.stream().map(Membership::getUserId).toList())
                    .stream()
                    .filter(profile -> profile.getFullName() != null)
                    .collect(Collectors.toMap(UserProfile::getId, UserProfile::getFullName));
            return team.stream()
                    .sorted(Comparator.comparing(Membership::getCreatedAt))
                    .map(member -> MemberView.of(member, names.get(member.getUserId())))
                    .toList();
        });
    }

    MemberView changeRole(UUID membershipId, Role role) {
        return change(membershipId, member -> {
            Role from = member.getRole();
            if (from == role) return false;
            member.changeRole(role);
            record(member, "member.role_changed", from.databaseValue(), role.databaseValue());
            return true;
        });
    }

    MemberView deactivate(UUID membershipId) {
        return change(membershipId, member -> switchStatus(member, ACTIVE, DEACTIVATED, "member.deactivated"));
    }

    MemberView reactivate(UUID membershipId) {
        return change(membershipId, member -> switchStatus(member, DEACTIVATED, ACTIVE, "member.reactivated"));
    }

    /** Moves a member from one status to another; already there is a no-op, anything else is a 409. */
    private boolean switchStatus(Membership member, String from, String to, String action) {
        if (member.getStatus().equals(to)) return false;
        if (!member.getStatus().equals(from)) {
            throw new ConflictException("invalid_status_change", "This member's status cannot change that way");
        }
        member.changeStatus(to);
        record(member, action, from, to);
        return true;
    }

    /**
     * Runs one change to one member. The target row is locked first (select ... for update), so a second admin changing
     * the same member waits, then sees the committed row and decides from it: a no-op, a 409, or a real change whose
     * audit "from" is the state it actually changed. Membership has @DynamicUpdate, so only changed columns are written.
     *
     * <p>Lock order, and why it cannot deadlock with the P14 trigger: each change locks exactly one membership row, and
     * only afterwards, during the update, may the trigger take the tenant's advisory lock. Nothing that holds the
     * advisory lock ever waits for a membership row: the trigger's re-check is a plain read, which takes no row lock.
     * So no transaction holds the advisory lock while waiting for a row another holds, and no cycle can form. Anything
     * unexpected (a deadlock, a lock timeout) still maps to 409 conflict_retry in {@link #translated}.
     */
    private MemberView change(UUID membershipId, Function<Membership, Boolean> work) {
        requireAdmin();
        return translated(() -> transactions.execute(status -> {
            Membership member = memberships
                    .findLockedById(membershipId)
                    .orElseThrow(() -> new NotFoundException("membership", membershipId));
            if (work.apply(member)) memberships.saveAndFlush(member);
            String name = profiles.findById(member.getUserId())
                    .map(UserProfile::getFullName)
                    .orElse(null);
            return MemberView.of(member, name);
        }));
    }

    private void record(Membership member, String action, String from, String to) {
        TenantContext actor = TenantScope.require();
        auditLog.save(AuditLogEntry.change(actor.userId(), action, "memberships", member.getId(), from, to));
    }

    private static void requireAdmin() {
        if (TenantScope.require().role() != Role.ADMIN) {
            throw new ForbiddenException("admin_only", "Only an admin can do this");
        }
    }

    /**
     * Turns the database's answer into the API's: the last-admin trigger (by SQLSTATE), the one-active-membership index
     * (D4, by constraint name), and lock trouble that is safe to retry. Never by message text.
     */
    static <T> T translated(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException failure) {
            String state = DatabaseErrors.sqlState(failure);
            if (DatabaseErrors.LAST_ADMIN.equals(state)) {
                throw new ConflictException("last_admin", "A business must keep at least one active admin");
            }
            if (DatabaseErrors.ONE_ACTIVE_MEMBERSHIP.equals(DatabaseErrors.constraintName(failure))) {
                throw new ConflictException("already_has_business", "This account already belongs to a business");
            }
            if (state != null && DatabaseErrors.RETRYABLE.contains(state)) {
                throw new ConflictException("conflict_retry", "This change clashed with another one; please try again");
            }
            throw failure;
        }
    }
}
