package com.northstar.workflow;

/** The mode an action ran under (workflow-spec.md section 9), stored as actions.mode. */
public enum ActionMode {
    APPROVAL("approval"),
    AUTOMATIC("automatic");

    private final String databaseValue;

    ActionMode(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
