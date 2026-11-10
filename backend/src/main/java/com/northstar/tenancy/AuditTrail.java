package com.northstar.tenancy;

import java.util.UUID;

/**
 * Writes audit_log entries for other modules (data-model.md section 10), inside the caller's tenant scope and
 * transaction. The actor is the scope's user; the system actor is recorded as no user. IDs and fixed codes only.
 */
public interface AuditTrail {

    /** An entry with no metadata, e.g. "action.approved" on an action. */
    void record(String action, String targetTable, UUID targetId);

    /** An entry recording a change between two fixed codes, as {"from", "to"} metadata. */
    void recordChange(String action, String targetTable, UUID targetId, String from, String to);
}
