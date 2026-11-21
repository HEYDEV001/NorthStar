package com.northstar.integrations.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business's WhatsApp integration as the sender and the sync see it (steps 2.6a and 2.8a; data-model.md section 9,
 * integration-specs.md section 1 Health): whether it is connected and which credential it uses, and its health after
 * each call to Meta. Native SQL with an explicit tenant filter on top of RLS (D7), as the system actor, MANDATORY: it
 * joins the sender's transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class WhatsAppConnection {

    static final String CONNECTED = "connected";

    private static final String CURRENT = """
            select status, credential_id from northstar.integrations
            where tenant_id = ? and provider = 'whatsapp'""";

    private static final String SUCCESS = """
            update northstar.integrations set last_success_at = now()
            where tenant_id = ? and provider = 'whatsapp'""";

    private static final String FAILURE = """
            update northstar.integrations set last_error_code = ?, last_error_at = now()
            where tenant_id = ? and provider = 'whatsapp'""";

    // An auth failure: the connection needs reconnecting, so every later send pauses before calling Meta.
    private static final String AUTH_FAILURE = """
            update northstar.integrations set status = 'error', last_error_code = ?, last_error_at = now()
            where tenant_id = ? and provider = 'whatsapp'""";

    private final JdbcClient jdbc;

    WhatsAppConnection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The integration's status and credential (null if none is stored), or empty if WhatsApp was never connected. */
    record Current(String status, UUID credentialId) {
        boolean usable() {
            return CONNECTED.equals(status) && credentialId != null;
        }
    }

    Optional<Current> current() {
        return jdbc.sql(CURRENT)
                .param(systemTenant())
                .query((row, n) -> new Current(row.getString("status"), row.getObject("credential_id", UUID.class)))
                .optional();
    }

    /** A call to Meta succeeded: a message accepted, or a number read. */
    void recordSuccess() {
        jdbc.sql(SUCCESS).param(systemTenant()).update();
    }

    /** A call to Meta failed with this fixed code; an auth failure also sets the status to error. */
    void recordFailure(String code, boolean auth) {
        Objects.requireNonNull(code, "code");
        jdbc.sql(auth ? AUTH_FAILURE : FAILURE)
                .param(code)
                .param(systemTenant())
                .update();
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Calls to Meta run as the system actor");
        return context.tenantId();
    }
}
