package com.northstar.contacts;

import java.util.UUID;

/**
 * An automated message (the engine's or the AI's) refused because a staff member has taken its conversation over
 * (step 2.9c): automation stays paused until it is handed back, and nothing is stored. Phase 3's engine and Phase 4's
 * AI check {@code automation_paused} before they act; this is the backstop at queueing. Names the conversation only.
 */
public class AutomationPausedException extends RuntimeException {

    private final UUID conversationId;

    public AutomationPausedException(UUID conversationId) {
        super("Automation is paused for conversation " + conversationId + ": a staff member has taken it over");
        this.conversationId = conversationId;
    }

    public UUID conversationId() {
        return conversationId;
    }
}
