package com.northstar.tenancy.internal;

import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@link AuditTrail} over audit_log. Joins the caller's transaction; the repository refuses work without one. */
@Service
class AuditTrailService implements AuditTrail {

    private final AuditLogRepository auditLog;

    AuditTrailService(AuditLogRepository auditLog) {
        this.auditLog = auditLog;
    }

    @Override
    public void record(String action, String targetTable, UUID targetId) {
        auditLog.save(new AuditLogEntry(actor(), action, targetTable, targetId));
    }

    @Override
    public void recordChange(String action, String targetTable, UUID targetId, String from, String to) {
        auditLog.save(AuditLogEntry.change(actor(), action, targetTable, targetId, from, to));
    }

    /** The system actor has no profile, so its entries name no user (actor_user_id references user_profiles). */
    private static UUID actor() {
        TenantContext context = TenantScope.require();
        return context.isSystem() ? null : context.userId();
    }
}
