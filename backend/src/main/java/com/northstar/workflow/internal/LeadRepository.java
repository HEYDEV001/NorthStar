package com.northstar.workflow.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Leads in the current tenant, as RLS lets the current role see them (P11). */
interface LeadRepository extends JpaRepository<Lead, UUID> {}
