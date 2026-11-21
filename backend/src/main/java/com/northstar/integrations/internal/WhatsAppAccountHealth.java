package com.northstar.integrations.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current tenant's WhatsApp numbers as the sync and the limit webhook see them (step 2.8a; data-model.md section 9,
 * integration-specs.md section 2, Quality and limits): which numbers there are, and their quality rating and messaging
 * limit. Native SQL with an explicit tenant filter on top of RLS (D7), as the system actor, MANDATORY: it joins the
 * caller's transaction.
 *
 * <p>Locks: one number's row ({@code for no key update}) to record what the sync read, or a WABA's rows in ID order to
 * record a limit webhook, so two limit webhooks of one WABA wait for each other in the same order. Nothing else is
 * locked, and the sync holds one row per transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class WhatsAppAccountHealth {

    private static final String NUMBERS = """
            select id, phone_number_id, quality_rating, messaging_limit from northstar.whatsapp_accounts
            where tenant_id = ?
            order by created_at, id""";

    private static final String LOCK_NUMBER = """
            select quality_rating, messaging_limit from northstar.whatsapp_accounts
            where tenant_id = ? and id = ?
            for no key update""";

    private static final String RECORD = """
            update northstar.whatsapp_accounts set quality_rating = ?, messaging_limit = ?
            where tenant_id = ? and id = ?""";

    private static final String LOCK_WABA = """
            select id, messaging_limit from northstar.whatsapp_accounts
            where tenant_id = ? and waba_id = ?
            order by id
            for no key update""";

    private static final String RECORD_LIMIT = """
            update northstar.whatsapp_accounts set messaging_limit = ?
            where tenant_id = ? and id = ?""";

    private final JdbcClient jdbc;

    WhatsAppAccountHealth(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One of the tenant's numbers; empty values were never read from Meta. */
    record Number(
            UUID accountId, String phoneNumberId, Optional<QualityRating> quality, Optional<MessagingLimit> limit) {

        @Override
        public String toString() {
            return "Number[accountId=" + accountId + "]";
        }
    }

    /** What recording changed: the values before and after (empty: none stored). */
    record Recorded(
            Optional<QualityRating> previousQuality,
            Optional<QualityRating> quality,
            Optional<MessagingLimit> previousLimit,
            Optional<MessagingLimit> limit) {

        boolean changed() {
            return !previousQuality.equals(quality) || !previousLimit.equals(limit);
        }

        /** Green to yellow or red, or yellow to red. */
        boolean qualityDropped() {
            return quality.isPresent()
                    && previousQuality.isPresent()
                    && quality.get().isDropFrom(previousQuality.get());
        }
    }

    /** The tenant's numbers, oldest first. */
    List<Number> numbers() {
        return jdbc.sql(NUMBERS)
                .param(systemTenant())
                .query((row, n) -> new Number(
                        row.getObject("id", UUID.class),
                        row.getString("phone_number_id"),
                        QualityRating.fromDatabase(row.getString("quality_rating")),
                        MessagingLimit.fromDatabase(row.getString("messaging_limit"))))
                .list();
    }

    /**
     * Records what the sync read for one number, under its row lock. A null value keeps what is stored (Meta did not
     * send one this program recognises). Empty if the tenant has no such number.
     */
    Optional<Recorded> record(UUID accountId, QualityRating quality, MessagingLimit limit) {
        Objects.requireNonNull(accountId, "accountId");
        UUID tenant = systemTenant();
        Optional<String[]> stored = jdbc.sql(LOCK_NUMBER)
                .param(tenant)
                .param(accountId)
                .query((row, n) -> new String[] {row.getString("quality_rating"), row.getString("messaging_limit")})
                .optional();
        if (stored.isEmpty()) return Optional.empty();
        Optional<QualityRating> previousQuality = QualityRating.fromDatabase(stored.get()[0]);
        Optional<MessagingLimit> previousLimit = MessagingLimit.fromDatabase(stored.get()[1]);
        Recorded recorded = new Recorded(
                previousQuality,
                quality != null ? Optional.of(quality) : previousQuality,
                previousLimit,
                limit != null ? Optional.of(limit) : previousLimit);
        if (recorded.changed()) {
            jdbc.sql(RECORD)
                    .param(recorded.quality().map(QualityRating::databaseValue).orElse(null))
                    .param(recorded.limit().map(MessagingLimit::databaseValue).orElse(null))
                    .param(tenant)
                    .param(accountId)
                    .update();
        }
        return Optional.of(recorded);
    }

    /** What a limit webhook changed: the WABA's numbers in this tenant, and how many now have a different limit. */
    record LimitRecorded(int numbers, int changed) {}

    /**
     * Records the business portfolio's messaging limit on every number of this WABA in the tenant (limits are set per
     * portfolio and shared by its numbers), its rows locked in ID order.
     */
    LimitRecorded recordLimit(String wabaId, MessagingLimit limit) {
        Objects.requireNonNull(wabaId, "wabaId");
        Objects.requireNonNull(limit, "limit");
        UUID tenant = systemTenant();
        List<String[]> rows = jdbc.sql(LOCK_WABA)
                .param(tenant)
                .param(wabaId)
                .query((row, n) -> new String[] {row.getString("id"), row.getString("messaging_limit")})
                .list();
        int changed = 0;
        for (String[] row : rows) {
            if (limit.databaseValue().equals(row[1])) continue;
            changed += jdbc.sql(RECORD_LIMIT)
                    .param(limit.databaseValue())
                    .param(tenant)
                    .param(UUID.fromString(row[0]))
                    .update();
        }
        return new LimitRecorded(rows.size(), changed);
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("WhatsApp numbers are synced by the system actor");
        return context.tenantId();
    }
}
