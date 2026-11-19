package com.northstar.tenancy;

/**
 * Who a unit of work runs as, set as app.role on every transaction. ADMIN and STAFF are member roles, as stored in
 * memberships.role. SYSTEM is the worker's actor (step 1.10): it is never stored, never parsed from the database and
 * never reachable from a web request (see {@link TenantContext#system}). WEBHOOK is the actor that stores a routed
 * webhook (step 2.2): it is never stored or parsed either, exists only for a tenant the webhook routing lookup returned,
 * and may run one statement, the webhook_events insert (see {@link WebhookRouting}).
 */
public enum Role {
    ADMIN("admin"),
    STAFF("staff"),
    SYSTEM("system"),
    WEBHOOK("webhook");

    private final String databaseValue;

    Role(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    /** A member role from the database. SYSTEM and WEBHOOK are refused: a membership can never grant them. */
    public static Role fromDatabaseValue(String value) {
        for (Role role : values()) {
            if (role != SYSTEM && role != WEBHOOK && role.databaseValue.equals(value)) return role;
        }
        throw new IllegalArgumentException("Unknown role");
    }
}
