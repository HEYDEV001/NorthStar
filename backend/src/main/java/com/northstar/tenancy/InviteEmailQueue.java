package com.northstar.tenancy;

import java.util.UUID;

/**
 * Queues the email that carries an invite's link (step 1.12). Tenancy calls it inside the transaction that creates the
 * invite; the implementation lives in a module that depends on tenancy (notifications), so tenancy depends on nothing
 * that sends email. It must join the caller's transaction: no invite, no email.
 */
public interface InviteEmailQueue {

    void queue(UUID inviteId);
}
