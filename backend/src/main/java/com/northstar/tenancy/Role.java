package com.northstar.tenancy;

/** A member's role in a tenant, as stored in memberships.role and set as app.role on every transaction. */
public enum Role {
    ADMIN("admin"),
    STAFF("staff");

    private final String databaseValue;

    Role(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    public static Role fromDatabaseValue(String value) {
        for (Role role : values()) {
            if (role.databaseValue.equals(value)) return role;
        }
        throw new IllegalArgumentException("Unknown role");
    }
}
