package com.northstar.integrations.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Integrations in the current tenant (data-model.md section 9). */
interface IntegrationRepository extends JpaRepository<Integration, UUID> {}
