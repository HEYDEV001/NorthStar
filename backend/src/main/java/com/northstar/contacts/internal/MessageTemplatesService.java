package com.northstar.contacts.internal;

import com.northstar.contacts.MessageTemplates;
import com.northstar.contacts.TemplateStatus;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link MessageTemplates} (step 2.8b). Native SQL with bind parameters and an explicit tenant filter on top of RLS
 * (D7), as the system actor only, which the restrictive template write policies allow. MANDATORY: each call joins the
 * caller's transaction, and locks only the one template row it reads.
 *
 * <p>A content update runs only when something differs; otherwise only meta_updated_at moves forward (its row's
 * updated_at with it, as the trigger stamps every update).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class MessageTemplatesService implements MessageTemplates {

    private static final String LOCK_BY_PROVIDER_ID = """
            select id, provider_template_id, status, category, meta_updated_at from northstar.message_templates
            where tenant_id = :tenant and provider_template_id = :providerId
            for no key update""";

    private static final String LOCK_BY_NAME = """
            select id, provider_template_id, status, category, meta_updated_at from northstar.message_templates
            where tenant_id = :tenant and name = :name and language = :language
            for no key update""";

    private static final String LOCK_BY_ID = """
            select id, provider_template_id, status, category, meta_updated_at from northstar.message_templates
            where tenant_id = :tenant and id = :id
            for no key update""";

    private static final String INSERT = """
            insert into northstar.message_templates
              (id, tenant_id, name, language, category, body, variables, provider_template_id, status, approved_at,
               meta_updated_at)
            values (:id, :tenant, :name, :language, :category, :body, cast(:variables as jsonb), :providerId, :status,
                    :approvedAt, :asOf)
            on conflict do nothing""";

    // A value Meta did not send (null) keeps what is stored; the first approval sets approved_at, which then stays.
    private static final String UPDATE_LISTED = """
            update northstar.message_templates
            set provider_template_id = :providerId,
                category = coalesce(cast(:category as text), category),
                body = coalesce(cast(:body as text), body),
                variables = coalesce(cast(:variables as jsonb), variables),
                status = :status,
                approved_at = case when cast(:status as text) = 'approved' then coalesce(approved_at, :asOf)
                                   else approved_at end,
                meta_updated_at = :asOf
            where tenant_id = :tenant and id = :id
              and (provider_template_id is distinct from cast(:providerId as text)
                   or (cast(:category as text) is not null and category is distinct from cast(:category as text))
                   or (cast(:body as text) is not null and body is distinct from cast(:body as text))
                   or (cast(:variables as jsonb) is not null
                       and variables is distinct from cast(:variables as jsonb))
                   or status is distinct from cast(:status as text)
                   or (cast(:status as text) = 'approved' and approved_at is null))""";

    private static final String UPDATE_STATUS = """
            update northstar.message_templates
            set status = :status,
                approved_at = case when cast(:status as text) = 'approved' then coalesce(approved_at, :asOf)
                                   else approved_at end,
                meta_updated_at = :asOf
            where tenant_id = :tenant and id = :id
              and (status is distinct from cast(:status as text)
                   or (cast(:status as text) = 'approved' and approved_at is null))""";

    private static final String UPDATE_CATEGORY = """
            update northstar.message_templates set category = :category, meta_updated_at = :asOf
            where tenant_id = :tenant and id = :id and category is distinct from cast(:category as text)""";

    private static final String TOUCH = """
            update northstar.message_templates set meta_updated_at = :asOf
            where tenant_id = :tenant and id = :id and (meta_updated_at is null or meta_updated_at < :asOf)""";

    private static final String MISSING = """
            select id from northstar.message_templates
            where tenant_id = :tenant and provider_template_id is not null and status <> 'deleted'
              and not (provider_template_id = any (cast(:listed as text[])))
            order by id""";

    private static final String FIND = """
            select id from northstar.message_templates
            where tenant_id = :tenant and provider_template_id = :providerId""";

    private final JdbcClient jdbc;

    MessageTemplatesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A locked template row: what ordering and outcomes need. */
    private record Row(
            UUID id, String providerId, TemplateStatus status, String category, OffsetDateTime metaUpdatedAt) {

        boolean newerThan(Instant asOf) {
            return metaUpdatedAt != null && metaUpdatedAt.toInstant().isAfter(asOf);
        }
    }

    @Override
    public Applied applyListed(Listed template, Set<String> listedIds, Instant asOf) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(listedIds, "listedIds");
        Objects.requireNonNull(asOf, "asOf");
        UUID tenant = systemTenant();
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<Row> byId = lock(LOCK_BY_PROVIDER_ID, tenant, "providerId", template.providerTemplateId());
            if (byId.isPresent()) return update(tenant, byId.get(), template, asOf, Outcome.UPDATED);

            Optional<Row> byName = jdbc.sql(LOCK_BY_NAME)
                    .param("tenant", tenant)
                    .param("name", template.name())
                    .param("language", template.language())
                    .query(MessageTemplatesService::row)
                    .optional();
            if (byName.isPresent()) {
                Row row = byName.get();
                if (row.providerId() == null) return update(tenant, row, template, asOf, Outcome.UPDATED);
                if (!listedIds.contains(row.providerId())) {
                    return update(tenant, row, template, asOf, Outcome.REPLACED);
                }
                return new Applied(
                        row.id(), Outcome.NAME_TAKEN, row.status(), row.status(), row.category(), row.category());
            }

            UUID id = UUID.randomUUID();
            int inserted = jdbc.sql(INSERT)
                    .param("id", id)
                    .param("tenant", tenant)
                    .param("name", template.name())
                    .param("language", template.language())
                    .param("category", template.category())
                    .param("body", template.body())
                    .param("variables", template.variablesJson())
                    .param("providerId", template.providerTemplateId())
                    .param("status", template.status().code())
                    .param("approvedAt", template.status() == TemplateStatus.APPROVED ? utc(asOf) : null)
                    .param("asOf", utc(asOf))
                    .update();
            if (inserted == 1) {
                return new Applied(id, Outcome.INSERTED, null, template.status(), null, template.category());
            }
            // Another sync inserted it first: look again, now that its row is there.
        }
        throw new IllegalStateException("A listed template could be neither found nor inserted");
    }

    private Applied update(UUID tenant, Row row, Listed template, Instant asOf, Outcome ifChanged) {
        if (row.newerThan(asOf)) return unchanged(row, Outcome.STALE);
        int changed = jdbc.sql(UPDATE_LISTED)
                .param("providerId", template.providerTemplateId())
                .param("category", template.category())
                .param("body", template.body())
                .param("variables", template.variablesJson())
                .param("status", template.status().code())
                .param("asOf", utc(asOf))
                .param("tenant", tenant)
                .param("id", row.id())
                .update();
        if (changed == 0) {
            touch(tenant, row.id(), asOf);
            return unchanged(row, Outcome.UNCHANGED);
        }
        String category = template.category() != null ? template.category() : row.category();
        return new Applied(row.id(), ifChanged, row.status(), template.status(), row.category(), category);
    }

    @Override
    public Optional<Applied> applyStatus(String providerTemplateId, TemplateStatus status, Instant asOf) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(asOf, "asOf");
        UUID tenant = systemTenant();
        return lock(LOCK_BY_PROVIDER_ID, tenant, "providerId", providerTemplateId)
                .map(row -> setStatus(tenant, row, status, asOf));
    }

    @Override
    public Optional<Applied> markDeleted(UUID templateId, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        UUID tenant = systemTenant();
        return lock(LOCK_BY_ID, tenant, "id", templateId)
                .map(row -> setStatus(tenant, row, TemplateStatus.DELETED, asOf));
    }

    private Applied setStatus(UUID tenant, Row row, TemplateStatus status, Instant asOf) {
        if (row.newerThan(asOf)) return unchanged(row, Outcome.STALE);
        int changed = jdbc.sql(UPDATE_STATUS)
                .param("status", status.code())
                .param("asOf", utc(asOf))
                .param("tenant", tenant)
                .param("id", row.id())
                .update();
        if (changed == 0) {
            touch(tenant, row.id(), asOf);
            return unchanged(row, Outcome.UNCHANGED);
        }
        return new Applied(row.id(), Outcome.UPDATED, row.status(), status, row.category(), row.category());
    }

    @Override
    public Optional<Applied> applyCategory(String providerTemplateId, String category, Instant asOf) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(asOf, "asOf");
        UUID tenant = systemTenant();
        return lock(LOCK_BY_PROVIDER_ID, tenant, "providerId", providerTemplateId)
                .map(row -> {
                    if (row.newerThan(asOf)) return unchanged(row, Outcome.STALE);
                    int changed = jdbc.sql(UPDATE_CATEGORY)
                            .param("category", category)
                            .param("asOf", utc(asOf))
                            .param("tenant", tenant)
                            .param("id", row.id())
                            .update();
                    if (changed == 0) {
                        touch(tenant, row.id(), asOf);
                        return unchanged(row, Outcome.UNCHANGED);
                    }
                    return new Applied(row.id(), Outcome.UPDATED, row.status(), row.status(), row.category(), category);
                });
    }

    @Override
    public List<UUID> missingFrom(Set<String> listedIds) {
        Objects.requireNonNull(listedIds, "listedIds");
        return jdbc.sql(MISSING)
                .param("tenant", systemTenant())
                .param("listed", listedIds.toArray(String[]::new))
                .query(UUID.class)
                .list();
    }

    @Override
    public Optional<UUID> findByProviderId(String providerTemplateId) {
        Objects.requireNonNull(providerTemplateId, "providerTemplateId");
        return jdbc.sql(FIND)
                .param("tenant", systemTenant())
                .param("providerId", providerTemplateId)
                .query(UUID.class)
                .optional();
    }

    private Optional<Row> lock(String sql, UUID tenant, String key, Object value) {
        Objects.requireNonNull(value, key);
        return jdbc.sql(sql)
                .param("tenant", tenant)
                .param(key, value)
                .query(MessageTemplatesService::row)
                .optional();
    }

    private void touch(UUID tenant, UUID id, Instant asOf) {
        jdbc.sql(TOUCH)
                .param("asOf", utc(asOf))
                .param("tenant", tenant)
                .param("id", id)
                .update();
    }

    private static Applied unchanged(Row row, Outcome outcome) {
        return new Applied(row.id(), outcome, row.status(), row.status(), row.category(), row.category());
    }

    private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("provider_template_id"),
                TemplateStatus.fromCode(rs.getString("status")).orElseThrow(),
                rs.getString("category"),
                rs.getObject("meta_updated_at", OffsetDateTime.class));
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Templates are synced from Meta by the system actor");
        return context.tenantId();
    }
}
