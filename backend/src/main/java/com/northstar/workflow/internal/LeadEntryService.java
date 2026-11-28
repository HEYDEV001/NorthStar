package com.northstar.workflow.internal;

import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.LeadEntry;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.LeadOrigin;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LeadEntry} (step 2.10; a new lead's first timeline row since step 3.3). Native SQL with bind parameters and an explicit tenant filter on top of RLS (D7).
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
            select id, contact_id, conversation_id, workflow_version_id, stage, previous_stage, stage_entered_at,
                   closed_at, version, origin, assigned_membership_id, created_at
            from northstar.leads where tenant_id = ? and id = ? and deleted_at is null""";

    private final JdbcClient jdbc;
    private final StageChanges changes;
    private final WorkflowGraphs graphs;
    private final TenantDirectory tenants;

    LeadEntryService(JdbcClient jdbc, StageChanges changes, WorkflowGraphs graphs, TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.changes = changes;
        this.graphs = graphs;
        this.tenants = tenants;
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
        UUID tenant = memberTenant();
        boolean opened = jdbc.sql(INSERT_LEAD)
                        .param(leadId)
                        .param(tenant)
                        .param(contactId)
                        .param(workflowVersionId)
                        .param(LeadIntake.FIRST_STAGE)
                        .param(LeadOrigin.MANUAL.code())
                        .param(assignedMembershipId)
                        .update()
                == 1;
        if (opened) {
            // Its first timeline row (step 3.3): the member who entered it, with no event behind it.
            UUID me = tenants.currentMembershipId()
                    .orElseThrow(() -> new IllegalStateException("A lead is entered by a member with a membership"));
            changes.recordCreated(
                    tenant,
                    leadId,
                    contactId,
                    workflowVersionId,
                    LeadIntake.FIRST_STAGE,
                    StageChanges.Actor.staff(me),
                    new StageChanges.Cause(null, null, null));
        }
        return opened;
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
        UUID tenant = memberTenant();
        return jdbc.sql(LEAD)
                .param(tenant)
                .param(leadId)
                .query((row, n) -> new StoredLead(
                        row.getObject("id", UUID.class),
                        row.getObject("contact_id", UUID.class),
                        row.getObject("conversation_id", UUID.class),
                        row.getObject("workflow_version_id", UUID.class),
                        row.getString("stage"),
                        row.getString("previous_stage"),
                        instant(row.getObject("stage_entered_at", OffsetDateTime.class)),
                        instant(row.getObject("closed_at", OffsetDateTime.class)),
                        row.getInt("version"),
                        row.getString("origin"),
                        row.getObject("assigned_membership_id", UUID.class),
                        instant(row.getObject("created_at", OffsetDateTime.class))))
                .optional()
                .map(lead -> lead.view(graphs.stageNames(tenant, lead.workflowVersionId(), lead.id())));
    }

    /** A lead as stored, before its stage names are read from its version. */
    private record StoredLead(
            UUID id,
            UUID contactId,
            UUID conversationId,
            UUID workflowVersionId,
            String stage,
            String previousStage,
            Instant stageEnteredAt,
            Instant closedAt,
            int version,
            String origin,
            UUID assignedMembershipId,
            Instant createdAt) {

        LeadView view(Map<String, String> names) {
            return new LeadView(
                    id,
                    contactId,
                    conversationId,
                    workflowVersionId,
                    stage,
                    names.get(stage),
                    previousStage,
                    previousStage == null ? null : names.get(previousStage),
                    stageEnteredAt,
                    closedAt,
                    version,
                    origin,
                    assignedMembershipId,
                    createdAt);
        }
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    private static UUID memberTenant() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Manual leads are entered by admins and staff only");
        }
        return context.tenantId();
    }
}
