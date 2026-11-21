package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantScope;
import com.northstar.tenancy.TenantSettings;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantSettings}. Native SQL with the tenant bound from the scope (RLS shows each tenant only its own row).
 * MANDATORY: it reads inside the caller's transaction, such as the inbound job's (step 2.5).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class TenantSettingsService implements TenantSettings {

    private static final String OPT_OUT_KEYWORDS = "select opt_out_keywords from northstar.tenants where id = ?";

    private final JdbcClient jdbc;

    TenantSettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> optOutKeywords() {
        return jdbc.sql(OPT_OUT_KEYWORDS)
                .param(TenantScope.require().tenantId())
                .query((row, index) -> keywords(row.getArray(1)))
                .optional()
                .orElse(List.of());
    }

    private static List<String> keywords(Array array) throws SQLException {
        if (array == null) return List.of();
        return List.copyOf(Arrays.asList((String[]) array.getArray()));
    }
}
