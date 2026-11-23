package com.northstar.contacts.internal;

import com.northstar.api.NotFoundException;
import com.northstar.contacts.ContactConsent;
import com.northstar.contacts.InboundMessageType;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.tenancy.TenantSettings;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ContactConsent} (step 2.5). Native SQL with bind parameters and an explicit tenant filter on top of RLS (D7).
 * MANDATORY: each call joins the caller's transaction, so an opt-out is recorded together with its message and the
 * cancellation of the contact's actions, or not at all.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ContactConsentService implements ContactConsent {

    static final String OPTED_OUT = "opted_out";

    /** Only these types carry text the client typed or tapped; every other type never opts out. */
    private static final Set<InboundMessageType> CHECKED_TYPES =
            EnumSet.of(InboundMessageType.TEXT, InboundMessageType.INTERACTIVE);

    private static final Set<String> PLATFORM_KEYWORDS =
            PLATFORM_OPT_OUT_KEYWORDS.stream().map(OptOutText::normalise).collect(Collectors.toUnmodifiableSet());

    // The inbound job already holds this lock (InboundContacts.lockContact); taking it again is a no-op that makes the
    // read safe for any other caller.
    private static final String LOCK_CONSENT = """
            select consent_state from northstar.contacts
            where tenant_id = ? and id = ?
            for update""";

    // opt_out_at keeps its first value (the database refuses moving it, NSC01); last_opt_out_at (step 2.9b) moves
    // forward to every later opt-out message, and never back for one that arrives late.
    private static final String RECORD_OPT_OUT = """
            update northstar.contacts
            set consent_state = 'opted_out', opt_out_at = coalesce(opt_out_at, :at),
                last_opt_out_at = greatest(coalesce(last_opt_out_at, :at), :at)
            where tenant_id = :tenant and id = :id""";

    // A shared lock: it waits for an opt-out in progress (the update lock above) and then reads its result.
    private static final String READ_CONSENT = """
            select consent_state = 'opted_out' from northstar.contacts
            where tenant_id = ? and id = ?
            for share""";

    // The database's own rule (step 2.9b, northstar.staff_reply_allowed_after_opt_out), the one the backstop
    // actions_refuse_opted_out applies with no margin, so the app and the database can never disagree on it.
    private static final String STAFF_REPLY_ALLOWED = """
            select northstar.staff_reply_allowed_after_opt_out(?, ?, ?, cast(? as integer) * interval '1 second')""";

    private final JdbcClient jdbc;
    private final TenantSettings settings;

    ContactConsentService(JdbcClient jdbc, TenantSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public boolean isOptOutMessage(InboundMessageType type, String body) {
        Objects.requireNonNull(type, "type");
        TenantScope.require();
        if (!CHECKED_TYPES.contains(type) || body == null) return false;
        String text = OptOutText.normalise(body);
        if (text.isEmpty()) return false;
        if (PLATFORM_KEYWORDS.contains(text)) return true;
        for (String keyword : settings.optOutKeywords()) {
            if (text.equals(OptOutText.normalise(keyword))) return true;
        }
        return false;
    }

    @Override
    public boolean recordOptOut(UUID contactId, Instant optedOutAt) {
        Objects.requireNonNull(contactId, "contactId");
        Objects.requireNonNull(optedOutAt, "optedOutAt");
        UUID tenant = systemTenant();
        String state = jdbc.sql(LOCK_CONSENT)
                .param(tenant)
                .param(contactId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("The contact to opt out is not in this tenant"));
        jdbc.sql(RECORD_OPT_OUT)
                .param("at", OffsetDateTime.ofInstant(optedOutAt, ZoneOffset.UTC))
                .param("tenant", tenant)
                .param("id", contactId)
                .update();
        return !OPTED_OUT.equals(state);
    }

    @Override
    public boolean isOptedOut(UUID contactId) {
        Objects.requireNonNull(contactId, "contactId");
        return jdbc.sql(READ_CONSENT)
                .param(TenantScope.require().tenantId())
                .param(contactId)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("contact", contactId));
    }

    @Override
    public boolean staffReplyAllowed(UUID contactId, UUID messageId, Duration margin) {
        Objects.requireNonNull(contactId, "contactId");
        Objects.requireNonNull(messageId, "messageId");
        if (margin == null || margin.isNegative()) throw new IllegalArgumentException("margin < 0");
        return Boolean.TRUE.equals(jdbc.sql(STAFF_REPLY_ALLOWED)
                .param(TenantScope.require().tenantId())
                .param(contactId)
                .param(messageId)
                .param(Math.toIntExact(margin.toSeconds()))
                .query(Boolean.class)
                .single());
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Opt-outs are recorded by the system actor");
        return context.tenantId();
    }
}
