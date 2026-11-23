package com.northstar.workflow.internal;

import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.LeadEntry;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.LeadOrigin;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LeadEntry} (step 2.10). Native SQL with bind parameters and an explicit tenant filter on top of RLS (D7).
 * MANDATORY: each call joins the entry's transaction, so a lead is stored with its contact or not at all.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class LeadEntryService implements LeadEntry {

    // The one-open-lead-per-contact index (data-model.md section 13) is the conflict target. On conflict checks the new
    // row against the select policy, which a staff member's own lead passes (leads_staff_assigned).
    private static final String INSERT_LEAD = """
            insert into northstar.leads
              (id, tenant_id, contact_id, workflow_version_id, stage, origin, assigned_membership_id)
            values (?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, contact_id) where closed_at is null and deleted_at is null do nothing""";

    private static final String OPEN_LEAD = """
            select id from northstar.leads
            where tenant_id = ? and contact_id = ? and closed_at is null and deleted_at is null""";

    private static final String LEAD = """
            select id, contact_id, conversation_id, workflow_version_id, stage, origin, assigned_membership_id,
                   created_at
            from northstar.leads where tenant_id = ? and id = ?""";

    private final JdbcClient jdbc;

    LeadEntryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> publishedWorkflowVersion() {
        List<UUID> versions = jdbc.sql(LeadIntakeService.PUBLISHED_VERSIONS)
                .param(memberTenant())
                .query(UUID.class)
                .list();
        return versions.size() == 1 ? Optional.of(versions.getFirst()) : Optional.empty();
    }

    @Override
    public boolean open(UUID leadId, UUID contactId, UUID workflowVersionId, UUID assignedMembershipId) {
        Objects.requireNonNull(leadId, "leadId");
        Objects.requireNonNull(contactId, "contactId");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId");
        Objects.requireNonNull(assignedMembershipId, "assignedMembershipId");
        return jdbc.sql(INSERT_LEAD)
                        .param(leadId)
                        .param(memberTenant())
                        .param(contactId)
                        .param(workflowVersionId)
                        .param(LeadIntake.FIRST_STAGE)
                        .param(LeadOrigin.MANUAL.code())
                        .param(assignedMembershipId)
                        .update()
                == 1;
    }

    @Override
    public Optional<UUID> visibleOpenLead(UUID contactId) {
        Objects.requireNonNull(contactId, "contactId");
        return jdbc.sql(OPEN_LEAD)
                .param(memberTenant())
                .param(contactId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<LeadView> lead(UUID leadId) {
        Objects.requireNonNull(leadId, "leadId");
        return jdbc.sql(LEAD)
                .param(memberTenant())
                .param(leadId)
                .query((row, n) -> new LeadView(
                        row.getObject("id", UUID.class),
                        row.getObject("contact_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("workflow_version_id", UUID.class),
                        row.getString("stage"),
                        row.getString("origin"),
                        row.getObject("assigned_membership_id", UUID.class),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private static UUID memberTenant() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Manual leads are entered by admins and staff only");
        }
        return context.tenantId();
    }
}
