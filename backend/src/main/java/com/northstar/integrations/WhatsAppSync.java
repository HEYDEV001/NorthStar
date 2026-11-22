package com.northstar.integrations;

import com.northstar.workflow.ActionView;

/**
 * Syncs a business's WhatsApp account from Meta (steps 2.8a and 2.8b, integration-specs.md section 2): one
 * {@value #SYNC_TYPE} action reads each of the business's numbers on the worker, with the business's token, and stores
 * its quality rating and messaging limit, then lists the account's templates and applies them. The worker syncs every
 * connected business once in each 6-hour block on its own; this is for a system job that needs a sync sooner, such as
 * the template webhook job when Meta names a template Northstar does not have (step 2.8b).
 *
 * <p>Runs in the caller's tenant scope, as the system actor only. Requests within the same 10 minutes share one action,
 * so a burst of them syncs once (P1).
 */
public interface WhatsAppSync {

    /** The action type that syncs a business's WhatsApp account. */
    String SYNC_TYPE = "whatsapp.sync";

    /** The sync action for this 10-minute block: created now, or the one already requested in it. */
    ActionView request();
}
