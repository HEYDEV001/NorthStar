package com.northstar.workflow.internal;

import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.api.UnprocessableException;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ConfigProblem;
import com.northstar.workflow.WorkflowConfigInvalidException;
import com.northstar.workflow.Workflows;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link Workflows} (step 3.1; workflow-spec.md sections 15 and 16). Native SQL with bind parameters and an explicit
 * tenant filter on top of RLS (D7): the documents are jsonb read and written as text, and publishing needs its locks.
 *
 * <p>Creating and publishing take a transaction advisory lock for the business's workflows, so two admins can neither
 * both create the business's one workflow nor both publish (one workflow per business, data-model.md section 15). A
 * draft save and a publish of the same workflow are ordered by its row lock; publish compares the hash of the draft it
 * found with the one the admin reviewed. The database backs every rule here (data-model.md sections 6 and 12): admin or
 * system writes only, versions immutable (NSW01), the current version only moving to the newest (NSW02).
 */
@Service
@Transactional
class WorkflowsService implements Workflows {

    static final String CREATED = "workflow.created";
    static final String PUBLISHED = "workflow.published";

    private static final int MAX_NAME = 100;

    // The same key form as keep_an_active_admin's lock: a namespace and the tenant.
    private static final String LOCK_WORKFLOWS = """
            select pg_catalog.pg_advisory_xact_lock(
              pg_catalog.hashtext('northstar.workflows'), pg_catalog.hashtext(cast(? as text)))""";

    private static final String VIEW = """
            select w.id, w.name, w.template, w.current_version_id, v.version as current_version,
                   w.draft_config::text as draft, w.created_at, w.updated_at
            from northstar.workflows w
            left join northstar.workflow_versions v on v.tenant_id = w.tenant_id and v.id = w.current_version_id
            """;

    private static final String WORKFLOW = VIEW + "where w.tenant_id = ? and w.id = ?";

    // The published workflow first, then the oldest: one per business in MVP, so normally the only one.
    private static final String CURRENT =
            VIEW + "where w.tenant_id = ? order by (w.current_version_id is null), w.created_at, w.id limit 1";

    private static final String ANY_WORKFLOW = """
            select exists (select 1 from northstar.workflows where tenant_id = ?)""";

    private static final String INSERT_WORKFLOW = """
            insert into northstar.workflows (tenant_id, name, template) values (?, ?, ?) returning id""";

    private static final String TEMPLATE = """
            select template from northstar.workflows where tenant_id = ? and id = ?""";

    private static final String SAVE_DRAFT = """
            update northstar.workflows set draft_config = cast(? as jsonb)
            where tenant_id = ? and id = ?
            returning template, draft_config::text as draft""";

    private static final String LOCK_WORKFLOW = """
            select template, draft_config::text as draft from northstar.workflows
            where tenant_id = ? and id = ?
            for update""";

    private static final String OTHER_PUBLISHED = """
            select exists (
              select 1 from northstar.workflows
              where tenant_id = ? and id <> ? and current_version_id is not null)""";

    private static final String NEXT_VERSION = """
            select coalesce(max(version), 0) + 1 from northstar.workflow_versions
            where tenant_id = ? and workflow_id = ?""";

    private static final String VERSION_COLUMNS =
            "id, workflow_id, version, config::text as config, published_by, published_at";

    private static final String INSERT_VERSION = """
            insert into northstar.workflow_versions (tenant_id, workflow_id, version, config, published_by)
            values (?, ?, ?, cast(? as jsonb), ?)
            returning\s""" + VERSION_COLUMNS;

    private static final String MAKE_CURRENT = """
            update northstar.workflows set current_version_id = ? where tenant_id = ? and id = ?""";

    private static final String VERSION = "select " + VERSION_COLUMNS
            + " from northstar.workflow_versions where tenant_id = ? and workflow_id = ? and version = ?";

    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final TenantDirectory tenants;

    WorkflowsService(JdbcClient jdbc, AuditTrail audit, TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tenants = tenants;
    }

    @Override
    public WorkflowView create(String name, String template) {
        UUID tenant = editor().tenantId();
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty() || cleaned.codePointCount(0, cleaned.length()) > MAX_NAME) {
            throw new UnprocessableException("workflow_name_invalid", "A workflow's name is 1 to 100 characters");
        }
        if (template == null || !ConfigFormatV1.TEMPLATES.contains(template)) {
            throw new UnprocessableException("template_unknown", "Not an industry template");
        }
        lockWorkflows(tenant);
        if (Boolean.TRUE.equals(
                jdbc.sql(ANY_WORKFLOW).param(tenant).query(Boolean.class).single())) {
            throw new ConflictException("workflow_exists", "This business already has a workflow");
        }
        UUID id = jdbc.sql(INSERT_WORKFLOW)
                .param(tenant)
                .param(cleaned)
                .param(template)
                .query(UUID.class)
                .single();
        audit.record(CREATED, "workflows", id);
        return view(tenant, id);
    }

    @Override
    public Optional<WorkflowView> current() {
        UUID tenant = editor().tenantId();
        return jdbc.sql(CURRENT).param(tenant).query(VIEW_ROW).optional();
    }

    @Override
    public WorkflowView get(UUID workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");
        return view(editor().tenantId(), workflowId);
    }

    @Override
    public DraftSaved saveDraft(UUID workflowId, String draft) {
        Objects.requireNonNull(workflowId, "workflowId");
        UUID tenant = editor().tenantId();
        ConfigJson.Parsed parsed = ConfigJson.parse(draft);
        if (!parsed.isStorable()) {
            // Another business's workflow is a 404 whatever the draft holds (P9).
            template(tenant, workflowId);
            throw new WorkflowConfigInvalidException(parsed.refused());
        }
        record Saved(String template, String draft) {}
        Saved saved = jdbc.sql(SAVE_DRAFT)
                .param(draft)
                .param(tenant)
                .param(workflowId)
                .query((row, n) -> new Saved(row.getString("template"), row.getString("draft")))
                .optional()
                .orElseThrow(() -> new NotFoundException("workflow", workflowId));
        return new DraftSaved(
                ConfigJson.hash(saved.draft()), ConfigValidator.validate(parsed.document(), saved.template()));
    }

    @Override
    public List<ConfigProblem> validate(UUID workflowId, String draft) {
        Objects.requireNonNull(workflowId, "workflowId");
        String template = template(editor().tenantId(), workflowId).orElse(null);
        ConfigJson.Parsed parsed = ConfigJson.parse(draft);
        if (!parsed.isStorable()) return parsed.refused();
        return ConfigValidator.validate(parsed.document(), template);
    }

    @Override
    public PublishedVersion publish(UUID workflowId, String draftHash) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(draftHash, "draftHash");
        TenantContext editor = editor();
        UUID tenant = editor.tenantId();
        lockWorkflows(tenant);
        record Locked(String template, String draft) {}
        Locked workflow = jdbc.sql(LOCK_WORKFLOW)
                .param(tenant)
                .param(workflowId)
                .query((row, n) -> new Locked(row.getString("template"), row.getString("draft")))
                .optional()
                .orElseThrow(() -> new NotFoundException("workflow", workflowId));
        if (Boolean.TRUE.equals(jdbc.sql(OTHER_PUBLISHED)
                .param(tenant)
                .param(workflowId)
                .query(Boolean.class)
                .single())) {
            throw new ConflictException(
                    "another_workflow_published", "Another workflow of this business is already published");
        }
        if (workflow.draft() == null) throw new ConflictException("draft_missing", "There is no draft to publish");
        if (!ConfigJson.hash(workflow.draft()).equals(draftHash)) {
            throw new ConflictException("draft_changed", "The draft changed since it was reviewed");
        }
        ObjectNode document = ConfigJson.stored(workflow.draft());
        List<ConfigProblem> problems = ConfigValidator.validate(document, workflow.template());
        if (!problems.isEmpty()) throw new WorkflowConfigInvalidException(problems);

        int version = jdbc.sql(NEXT_VERSION)
                .param(tenant)
                .param(workflowId)
                .query(Integer.class)
                .single();
        // What publishing writes into the document (workflow-spec.md section 15); the database checks both.
        document.put("workflow_id", workflowId.toString());
        document.put("version", version);
        UUID publishedBy = editor.isSystem()
                ? null
                : tenants.currentMembershipId()
                        .orElseThrow(
                                () -> new ForbiddenException("no_active_membership", "This account has no membership"));
        PublishedVersion published = jdbc.sql(INSERT_VERSION)
                .param(tenant)
                .param(workflowId)
                .param(version)
                .param(ConfigJson.write(document))
                .param(publishedBy)
                .query(VERSION_ROW)
                .single();
        jdbc.sql(MAKE_CURRENT)
                .param(published.id())
                .param(tenant)
                .param(workflowId)
                .update();
        audit.record(PUBLISHED, "workflow_versions", published.id());
        return published;
    }

    @Override
    public PublishedVersion publishedVersion(UUID workflowId, int version) {
        Objects.requireNonNull(workflowId, "workflowId");
        return jdbc.sql(VERSION)
                .param(editor().tenantId())
                .param(workflowId)
                .param(version)
                .query(VERSION_ROW)
                .optional()
                .orElseThrow(() -> new NotFoundException("workflow", workflowId));
    }

    /** Only an admin or the system actor edits workflows (build-document.md section 3). */
    private static TenantContext editor() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && !context.isSystem()) {
            throw new ForbiddenException("admin_only", "Only an admin can do this");
        }
        return context;
    }

    private void lockWorkflows(UUID tenant) {
        jdbc.sql(LOCK_WORKFLOWS).param(tenant).query((row, n) -> Boolean.TRUE).single();
    }

    private WorkflowView view(UUID tenant, UUID workflowId) {
        return jdbc.sql(WORKFLOW)
                .param(tenant)
                .param(workflowId)
                .query(VIEW_ROW)
                .optional()
                .orElseThrow(() -> new NotFoundException("workflow", workflowId));
    }

    /** The workflow's template (empty if it has none), or 404. */
    private Optional<String> template(UUID tenant, UUID workflowId) {
        record Template(String value) {}
        Template template = jdbc.sql(TEMPLATE)
                .param(tenant)
                .param(workflowId)
                .query((row, n) -> new Template(row.getString("template")))
                .optional()
                .orElseThrow(() -> new NotFoundException("workflow", workflowId));
        return Optional.ofNullable(template.value());
    }

    private static final RowMapper<WorkflowView> VIEW_ROW = (row, n) -> {
        String draft = row.getString("draft");
        return new WorkflowView(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("template"),
                row.getObject("current_version_id", UUID.class),
                row.getObject("current_version", Integer.class),
                draft,
                draft == null ? null : ConfigJson.hash(draft),
                instant(row.getObject("created_at", OffsetDateTime.class)),
                instant(row.getObject("updated_at", OffsetDateTime.class)));
    };

    private static final RowMapper<PublishedVersion> VERSION_ROW = (row, n) -> new PublishedVersion(
            row.getObject("id", UUID.class),
            row.getObject("workflow_id", UUID.class),
            row.getInt("version"),
            row.getString("config"),
            row.getObject("published_by", UUID.class),
            instant(row.getObject("published_at", OffsetDateTime.class)));

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
