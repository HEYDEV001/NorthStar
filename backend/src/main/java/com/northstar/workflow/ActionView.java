package com.northstar.workflow;

import java.time.Instant;
import java.util.UUID;

/** An action's state, for the engine and the Failed Actions screen. Error fields are fixed codes only. */
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
        Instant createdAt) {}
