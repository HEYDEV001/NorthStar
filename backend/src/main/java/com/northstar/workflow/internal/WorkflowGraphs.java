package com.northstar.workflow.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads a lead's own workflow version as a {@link WorkflowGraph} (step 3.3, W36), inside the caller's transaction and
 * tenant. Versions are readable by every role (they hold no personal data) and never change, but are read again on each
 * move: moves are rare, and nothing is cached across tenants.
 *
 * <p>An unsupported version is logged once per version for the life of this instance, with IDs and a fixed code only,
 * so a lead stuck on one does not log on every event.
 */
@Component
class WorkflowGraphs {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGraphs.class);

    private static final String VERSION_CONFIG = """
            select config::text from northstar.workflow_versions where tenant_id = ? and id = ?""";

    private final JdbcClient jdbc;
    private final Set<UUID> reported = ConcurrentHashMap.newKeySet();

    WorkflowGraphs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The lead's version as a graph, or empty when the engine cannot run it. */
    Optional<WorkflowGraph> forLead(UUID tenant, UUID workflowVersionId, UUID leadId) {
        String config = jdbc.sql(VERSION_CONFIG)
                .param(tenant)
                .param(workflowVersionId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("A lead's workflow version is its own (foreign key)"));
        WorkflowGraph.Parsed parsed = WorkflowGraph.parse(config);
        if (parsed.graph() != null) return Optional.of(parsed.graph());
        if (reported.add(workflowVersionId)) {
            log.warn(
                    "workflow.version_unsupported workflow_version_id={} lead_id={} reason={}",
                    workflowVersionId,
                    leadId,
                    parsed.unsupported());
        }
        return Optional.empty();
    }
}
