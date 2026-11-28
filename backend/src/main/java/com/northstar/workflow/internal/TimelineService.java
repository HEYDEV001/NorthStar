package com.northstar.workflow.internal;

import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.Timeline;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * {@link Timeline} (step 3.3): keyset pages on {@code timeline_events_lead_history} (tenant, lead, time desc, ID desc),
 * fetching one row more than the page to know whether another follows. The lead is looked up first as the caller sees
 * it, so a lead they cannot see is no timeline at all rather than an empty one; RLS on the rows is the backstop. Native
 * SQL with bind parameters and an explicit tenant filter (D7), inside the caller's transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class TimelineService implements Timeline {

    private static final String VISIBLE_LEAD = """
            select exists (
              select 1 from northstar.leads where tenant_id = ? and id = ? and deleted_at is null)""";

    private static final String COLUMNS = """
            select id, type, actor_type, actor_membership_id, ref_table, ref_id, summary, data::text as data,
                   occurred_at
            from northstar.timeline_events
            where tenant_id = ? and lead_id = ?""";

    private static final String FIRST_PAGE = COLUMNS + " order by occurred_at desc, id desc limit ?";

    private static final String NEXT_PAGE =
            COLUMNS + " and (occurred_at, id) < (?, ?) order by occurred_at desc, id desc limit ?";

    private final JdbcClient jdbc;

    TimelineService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Page> page(UUID leadId, Position after, int limit) {
        Objects.requireNonNull(leadId, "leadId");
        if (limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("limit must be 1 to " + MAX_PAGE);
        UUID tenant = TenantScope.require().tenantId();
        boolean visible = Boolean.TRUE.equals(jdbc.sql(VISIBLE_LEAD)
                .param(tenant)
                .param(leadId)
                .query(Boolean.class)
                .single());
        if (!visible) return Optional.empty();

        List<Entry> rows = after == null
                ? jdbc.sql(FIRST_PAGE)
                        .param(tenant)
                        .param(leadId)
                        .param(limit + 1)
                        .query(ENTRY)
                        .list()
                : jdbc.sql(NEXT_PAGE)
                        .param(tenant)
                        .param(leadId)
                        .param(OffsetDateTime.ofInstant(
                                after.occurredAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC))
                        .param(after.id())
                        .param(limit + 1)
                        .query(ENTRY)
                        .list();
        if (rows.size() <= limit) return Optional.of(new Page(rows, null));
        List<Entry> page = rows.subList(0, limit);
        Entry last = page.getLast();
        return Optional.of(new Page(page, new Position(last.occurredAt(), last.id())));
    }

    private static final RowMapper<Entry> ENTRY = (row, n) -> new Entry(
            row.getObject("id", UUID.class),
            row.getString("type"),
            row.getString("actor_type"),
            row.getObject("actor_membership_id", UUID.class),
            row.getString("ref_table"),
            row.getObject("ref_id", UUID.class),
            row.getString("summary"),
            row.getString("data"),
            row.getObject("occurred_at", OffsetDateTime.class).toInstant());
}
