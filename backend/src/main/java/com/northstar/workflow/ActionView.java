package com.northstar.workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * An action's state, for the engine and the Failed Actions screen. Error fields and the cancel reason are fixed codes
 * only. recipientContactId is the contact the action messages (null: nobody outside the business).
 */
public record ActionView(
        UUID id,
        UUID leadId,
        String type,
        String status,
        String mode,
        int attempts,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        String lastErrorClass,
        String lastErrorCode,
        UUID approverMembershipId,
        Instant approvedAt,
        Instant createdAt,
        UUID recipientContactId,
        String cancelReason) {}
