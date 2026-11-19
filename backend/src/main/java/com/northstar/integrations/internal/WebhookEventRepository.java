package com.northstar.integrations.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Routed webhook events in the current tenant (data-model.md section 9). */
interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {}
