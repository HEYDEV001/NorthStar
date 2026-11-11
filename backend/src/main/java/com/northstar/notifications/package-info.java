/**
 * Notifications: telling people about platform events, starting with the staff invite email (step 1.12). Owns no
 * tables. It depends on tenancy, workflow and integrations through their public interfaces, and nothing depends on it,
 * so module dependencies keep pointing one way (ModuleDependencyTests).
 *
 * <p>Other modules use this module only through its public service interfaces, never its internals.
 */
package com.northstar.notifications;
