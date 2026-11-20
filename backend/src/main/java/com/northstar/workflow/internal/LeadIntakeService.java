package com.northstar.workflow.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.LeadIntake;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LeadIntake} (step 2.4b). Native SQL for the insert on the open-lead index and the guarded link (D7), with bind
 * parameters and an explicit tenant filter on top of RLS. MANDATORY: each call joins the inbound job's transaction, so
 * a lead is created together with its message or not at all (P3).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class LeadIntakeService implements LeadIntake {

    private static final String OPEN_LEAD = """
            select id, conversation_id, assigned_membership_id from northstar.leads
            where tenant_id = ? and contact_id = ? and closed_at is null and deleted_at is null""";

    private static final String LEAD = """
            select id, conversation_id, assigned_membership_id from northstar.leads
            where tenant_id = ? and id = ?""";

    // At most two rows: one means the tenant's workflow, two means more than one is published (no lead is opened).
    private static final String PUBLISHED_VERSIONS = """
            select current_version_id from northstar.workflows
            where tenant_id = ? and current_version_id is not null
            order by id
            limit 2""";

    // A change to the lead, so its optimistic lock moves on (P7).
    private static final String LINK_CONVERSATION = """
            update northstar.leads set conversation_id = ?, version = version + 1
            where tenant_id = ? and id = ? and conversation_id is null""";

    // The one-open-lead-per-contact index (data-model.md section 13) is the conflict target.
    private static final String INSERT_LEAD = """
            insert into northstar.leads
              (tenant_id, contact_id, conversation_id, workflow_version_id, stage, origin, assigned_membership_id)
            values (?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, contact_id) where closed_at is null and deleted_at is null do nothing
            returning id""";

    private final JdbcClient jdbc;

    LeadIntakeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OpenLead> findOpenLead(UUID contactId) {
        Objects.requireNonNull(contactId, "contactId");
        return jdbc.sql(OPEN_LEAD)
                .param(systemTenant())
                .param(contactId)
                .query(lead(false))
                .optional();
    }

    @Override
    public Optional<UUID> publishedWorkflowVersion() {
        List<UUID> versions = jdbc.sql(PUBLISHED_VERSIONS)
                .param(systemTenant())
                .query(UUID.class)
                .list();
        return versions.size() == 1 ? Optional.of(versions.getFirst()) : Optional.empty();
    }

    @Override
    public OpenLead linkConversation(OpenLead lead, UUID conversationId) {
        Objects.requireNonNull(lead, "lead");
        Objects.requireNonNull(conversationId, "conversationId");
        if (lead.conversationId() != null) return lead;
        UUID tenant = systemTenant();
        jdbc.sql(LINK_CONVERSATION)
                .param(conversationId)
                .param(tenant)
                .param(lead.id())
                .update();
        return jdbc.sql(LEAD)
                .param(tenant)
                .param(lead.id())
                .query(lead(lead.created()))
                .single();
    }

    @Override
    public OpenLead open(NewLead lead) {
        Objects.requireNonNull(lead, "lead");
        UUID tenant = systemTenant();
        Optional<UUID> created = jdbc.sql(INSERT_LEAD)
                .param(tenant)
                .param(lead.contactId())
                .param(lead.conversationId())
                .param(lead.workflowVersionId())
                .param(FIRST_STAGE)
                .param(lead.origin().code())
                .param(lead.assignedMembershipId())
                .query(UUID.class)
                .optional();
        if (created.isPresent()) {
            return new OpenLead(created.get(), lead.conversationId(), lead.assignedMembershipId(), true);
        }
        OpenLead existing = findOpenLead(lead.contactId())
                .orElseThrow(() -> new IllegalStateException("The open lead that blocked the insert is gone"));
        return linkConversation(existing, lead.conversationId());
    }

    private static RowMapper<OpenLead> lead(boolean created) {
        return (row, index) -> new OpenLead(
                row.getObject("id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getObject("assigned_membership_id", UUID.class),
                created);
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Inbound messages are processed by the system actor");
        return context.tenantId();
    }
}
