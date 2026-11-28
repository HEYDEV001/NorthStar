package com.northstar.workflow.internal;

import static com.northstar.workflow.internal.ConfigFormatV1.JUNK;
import static com.northstar.workflow.internal.ConfigFormatV1.LOST;
import static com.northstar.workflow.internal.ConfigFormatV1.NEEDS_HUMAN;

import com.northstar.workflow.LeadMoves.LeadStage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one writer of stage changes (step 3.3, workflow-spec.md section 3; data-model.md sections 6 and 10): it reads a
 * lead as the caller sees it, changes its stage in one update that checks the version it read (P7), and writes the
 * change's {@code stage_changed} timeline row. Native SQL with bind parameters and an explicit tenant filter on top of
 * RLS (D7), inside the caller's transaction.
 *
 * <p>The update locks only the lead's row, and as a no-key update (contact_id and id never change), so the foreign key
 * checks of rows referencing the lead never wait for it. A change's time is the database's clock at the update
 * ({@code clock_timestamp()}), so several changes in one transaction keep their order on the timeline; a lead's
 * creation row takes the lead's own creation time.
 */
@Component
class StageChanges {

    static final String STAGE_CHANGED = "stage_changed";

    private static final String LEAD = """
            select id, contact_id, workflow_version_id, stage, previous_stage, stage_entered_at, closed_at, version
            from northstar.leads
            where tenant_id = ? and id = ? and deleted_at is null""";

    // The contact's other open lead, as the caller sees it (a staff member only their own; the one-open-lead index is
    // the backstop for the rest).
    private static final String OTHER_OPEN_LEAD = """
            select exists (
              select 1 from northstar.leads
              where tenant_id = ? and contact_id = ? and id <> ? and closed_at is null and deleted_at is null)""";

    // closed_at is kept between two closing stages, set on entering one, and cleared on leaving one
    // (leads_closed_in_closing_stage). previous_stage is set exactly in Needs Human
    // (leads_previous_stage_in_needs_human).
    private static final String MOVE = """
            update northstar.leads
            set stage = ?, previous_stage = ?, stage_entered_at = clock_timestamp(), version = version + 1,
                closed_at = case when cast(? as boolean) then coalesce(closed_at, clock_timestamp()) end,
                lost_reason = ?, junk_rule_id = ?
            where tenant_id = ? and id = ? and version = ? and stage = ? and deleted_at is null
            returning stage_entered_at, closed_at, version""";

    private static final String INSERT_ROW = """
            insert into northstar.timeline_events
              (id, tenant_id, lead_id, contact_id, type, actor_type, actor_membership_id, ref_table, ref_id, summary,
               data, occurred_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), coalesce(cast(? as timestamptz), now()))""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    StageChanges(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A lead as a move reads it. previousStage is set only in Needs Human, closedAt only in a closing stage. */
    record LeadRow(
            UUID id,
            UUID contactId,
            UUID workflowVersionId,
            String stage,
            String previousStage,
            Instant stageEnteredAt,
            Instant closedAt,
            int version) {

        LeadStage view() {
            return new LeadStage(id, workflowVersionId, stage, previousStage, stageEnteredAt, closedAt, version);
        }
    }

    /** A change made: the lead before it, and after it. */
    record Applied(LeadRow before, LeadRow after) {}

    /** Who a timeline row names: a staff actor (an admin or a staff member) by membership, any other by type only. */
    record Actor(String type, UUID membershipId) {

        Actor {
            Objects.requireNonNull(type, "type");
            if (type.equals("staff") != (membershipId != null)) {
                throw new IllegalArgumentException("A staff actor, and only one, names its membership");
            }
        }

        static Actor staff(UUID membershipId) {
            return new Actor("staff", Objects.requireNonNull(membershipId, "membershipId"));
        }

        static Actor of(String type) {
            return new Actor(type, null);
        }
    }

    /** What caused a change, for its timeline row: the section 6 event type (or null), and the row it references. */
    record Cause(String trigger, String refTable, UUID refId) {

        Cause {
            if ((refTable == null) != (refId == null))
                throw new IllegalArgumentException("A ref has a table and an ID");
        }
    }

    /** The lead as the caller sees it: empty for one they cannot see, a deleted one or none. */
    Optional<LeadRow> read(UUID tenant, UUID leadId) {
        return jdbc.sql(LEAD).param(tenant).param(leadId).query(LEAD_ROW).optional();
    }

    /** Whether the contact has an open lead other than this one that the caller can see. */
    boolean otherOpenLead(UUID tenant, UUID contactId, UUID leadId) {
        return Boolean.TRUE.equals(jdbc.sql(OTHER_OPEN_LEAD)
                .param(tenant)
                .param(contactId)
                .param(leadId)
                .query(Boolean.class)
                .single());
    }

    /**
     * Moves the lead from the stage and version it was read at to {@code to}. Empty when it changed meanwhile (another
     * version or stage, or deleted): nothing is written. A re-opened lead whose contact has another open lead fails the
     * update and the caller's transaction (the one-open-lead index, a {@code DuplicateKeyException}).
     */
    Optional<Applied> move(UUID tenant, LeadRow lead, String to, MoveKind kind, String reason, String ruleId) {
        Objects.requireNonNull(to, "to");
        if (to.equals(lead.stage())) throw new IllegalArgumentException("A move changes the stage");
        return jdbc.sql(MOVE)
                .param(to)
                .param(NEEDS_HUMAN.equals(to) ? lead.stage() : null)
                .param(WorkflowGraph.isClosing(to))
                .param(LOST.equals(to) ? reason : null)
                .param(JUNK.equals(to) && kind == MoveKind.FILTER_RULE ? ruleId : null)
                .param(tenant)
                .param(lead.id())
                .param(lead.version())
                .param(lead.stage())
                .query((row, n) -> new Applied(
                        lead,
                        new LeadRow(
                                lead.id(),
                                lead.contactId(),
                                lead.workflowVersionId(),
                                to,
                                NEEDS_HUMAN.equals(to) ? lead.stage() : null,
                                instant(row.getObject("stage_entered_at", OffsetDateTime.class)),
                                instant(row.getObject("closed_at", OffsetDateTime.class)),
                                row.getInt("version"))))
                .optional();
    }

    /** The change's timeline row (data-model.md section 10). Returns its ID. */
    UUID recordChange(
            UUID tenant, Applied change, MoveKind kind, Actor actor, Cause cause, String reason, String ruleId) {
        ObjectNode data = json.createObjectNode();
        data.put("from", change.before().stage());
        data.put("to", change.after().stage());
        data.put("trigger", cause.trigger());
        data.put("reason", reason);
        if (kind == MoveKind.FILTER_RULE) data.put("rule_id", ruleId);
        data.put("forced", kind == MoveKind.FORCED);
        data.put("workflow_version_id", change.after().workflowVersionId().toString());
        data.put("lead_version", change.after().version());
        return insert(
                tenant,
                change.after().id(),
                change.after().contactId(),
                actor,
                cause,
                kind,
                data,
                change.after().stageEnteredAt());
    }

    /**
     * A new lead's first row (— → New, kind {@code created}), in the transaction that creates it, at the lead's own
     * creation time. Returns its ID.
     */
    UUID recordCreated(
            UUID tenant, UUID leadId, UUID contactId, UUID workflowVersionId, String stage, Actor actor, Cause cause) {
        ObjectNode data = json.createObjectNode();
        data.putNull("from");
        data.put("to", stage);
        data.put("trigger", cause.trigger());
        data.putNull("reason");
        data.put("forced", false);
        data.put("workflow_version_id", workflowVersionId.toString());
        data.put("lead_version", 0);
        return insert(tenant, leadId, contactId, actor, cause, MoveKind.CREATED, data, null);
    }

    private UUID insert(
            UUID tenant,
            UUID leadId,
            UUID contactId,
            Actor actor,
            Cause cause,
            MoveKind kind,
            ObjectNode data,
            Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT_ROW)
                .param(id)
                .param(tenant)
                .param(leadId)
                .param(contactId)
                .param(STAGE_CHANGED)
                .param(actor.type())
                .param(actor.membershipId())
                .param(cause.refTable())
                .param(cause.refId())
                .param(kind.code())
                .param(json.writeValueAsString(data))
                .param(occurredAt == null ? null : OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .update();
        return id;
    }

    private static final RowMapper<LeadRow> LEAD_ROW = (row, n) -> new LeadRow(
            row.getObject("id", UUID.class),
            row.getObject("contact_id", UUID.class),
            row.getObject("workflow_version_id", UUID.class),
            row.getString("stage"),
            row.getString("previous_stage"),
            instant(row.getObject("stage_entered_at", OffsetDateTime.class)),
            instant(row.getObject("closed_at", OffsetDateTime.class)),
            row.getInt("version"));

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
