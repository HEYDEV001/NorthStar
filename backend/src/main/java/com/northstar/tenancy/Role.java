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
}
