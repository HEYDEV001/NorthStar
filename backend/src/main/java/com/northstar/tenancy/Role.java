package com.northstar.tenancy;

/**
 * Who a unit of work runs as, set as app.role on every transaction. ADMIN and STAFF are member roles, as stored in
 * memberships.role. SYSTEM is the worker's actor (step 1.10): it is never stored, never parsed from the database and
 * never reachable from a web request (see {@link TenantContext#system}).
 */
public enum Role {
    ADMIN("admin"),
    STAFF("staff"),
    SYSTEM("system");

    private final String databaseValue;

    Role(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    /** A member role from the database. SYSTEM is refused: a membership can never grant it. */
    public static Role fromDatabaseValue(String value) {
        for (Role role : values()) {
            if (role != SYSTEM && role.databaseValue.equals(value)) return role;
        }
        throw new IllegalArgumentException("Unknown role");
    }
}
