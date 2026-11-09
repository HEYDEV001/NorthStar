package com.northstar.tenancy.internal;

import com.northstar.api.ConflictException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a business and makes the caller its first admin (step 1.7, D4). The new tenant's ID is generated here, and
 * everything happens in one transaction inside a tenant scope for that ID with role admin, so RLS and the admin-only
 * policies apply exactly as they do to every later request: the profile (if new), the tenant, the membership and the
 * audit entry are written together or not at all.
 */
@Service
class BusinessCreation {

    private static final String ONE_ACTIVE_MEMBERSHIP = "memberships_one_active_per_user";
    private static final String SLUG_UNIQUE = "tenants_slug_key";

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final AuditLogRepository auditLog;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    BusinessCreation(
            TenantRepository tenants,
            MembershipRepository memberships,
            AuditLogRepository auditLog,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.auditLog = auditLog;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    CreatedBusinessResponse create(UUID userId, CreateBusinessRequest request) {
        // A member arrives inside their own tenant scope (D4: one active membership per user). Refuse before opening
        // a scope for the new tenant, which would otherwise be a forbidden nested switch.
        if (TenantScope.current().isPresent()) throw alreadyHasBusiness();

        UUID tenantId = UUID.randomUUID();
        TenantContext context = new TenantContext(tenantId, userId, Role.ADMIN);
        try {
            return TenantScope.call(context, () -> transactions.execute(status -> createInScope(userId, request)));
        } catch (DataIntegrityViolationException e) {
            String constraint = constraintName(e);
            if (ONE_ACTIVE_MEMBERSHIP.equals(constraint)) throw alreadyHasBusiness(); // lost a race with itself
            if (SLUG_UNIQUE.equals(constraint)) throw slugTaken(); // checked free, taken before the insert
            throw e;
        }
    }

    private CreatedBusinessResponse createInScope(UUID userId, CreateBusinessRequest request) {
        UUID tenantId = TenantScope.require().tenantId();
        jdbc.sql("insert into northstar.user_profiles (id) values (:id) on conflict (id) do nothing")
                .param("id", userId)
                .update();

        String slug = request.slug() != null ? requestedSlug(request.slug()) : generatedSlug(request.name());
        Tenant tenant =
                tenants.saveAndFlush(Tenant.create(tenantId, request.name().trim(), slug));
        memberships.saveAndFlush(new Membership(userId, Role.ADMIN, "active"));
        auditLog.saveAndFlush(new AuditLogEntry(userId, "business.created", "tenants", tenantId));

        return new CreatedBusinessResponse(BusinessView.of(tenant), Role.ADMIN.databaseValue());
    }

    private String requestedSlug(String slug) {
        if (Slugs.isReserved(slug)) throw new ConflictException("slug_reserved", "That slug is reserved");
        if (isTaken(slug)) throw slugTaken();
        return slug;
    }

    /** The first free candidate: the name's slug, then -2 … -20, then a random suffix. Reserved words count as taken. */
    private String generatedSlug(String name) {
        String base = Slugs.fromName(name);
        for (int attempt = 1; ; attempt++) {
            String candidate = Slugs.variant(base, attempt);
            if (!Slugs.isReserved(candidate) && !isTaken(candidate)) return candidate;
        }
    }

    private boolean isTaken(String slug) {
        return Boolean.TRUE.equals(jdbc.sql("select northstar.slug_is_taken(:slug)")
                .param("slug", slug)
                .query(Boolean.class)
                .single());
    }

    private static String constraintName(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) return violation.getConstraintName();
        }
        return null;
    }

    private static ConflictException alreadyHasBusiness() {
        return new ConflictException("already_has_business", "This account already belongs to a business");
    }

    private static ConflictException slugTaken() {
        return new ConflictException("slug_taken", "That slug is already taken");
    }
}
