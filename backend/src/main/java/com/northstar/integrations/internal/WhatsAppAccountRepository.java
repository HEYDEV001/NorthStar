package com.northstar.integrations.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * WhatsApp accounts in the current tenant. Routing a webhook before its tenant is known goes through the allowlisted
 * northstar.whatsapp_tenants() instead ({@link com.northstar.tenancy.WebhookRouting}, step 2.2).
 */
interface WhatsAppAccountRepository extends JpaRepository<WhatsAppAccount, UUID> {}
