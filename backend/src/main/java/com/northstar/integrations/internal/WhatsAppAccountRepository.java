package com.northstar.integrations.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** WhatsApp accounts in the current tenant. Routing a webhook before the tenant is known needs its own allowlisted lookup (step 2.2). */
interface WhatsAppAccountRepository extends JpaRepository<WhatsAppAccount, UUID> {}
