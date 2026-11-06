package com.northstar.tenancy.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {}
