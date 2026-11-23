package com.northstar.contacts.internal;

import com.northstar.contacts.ContactSource;
import com.northstar.contacts.ManualContacts;
import com.northstar.contacts.PhoneNumbers;
import com.northstar.contacts.PhoneTakenException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ManualContacts} (step 2.10). Native SQL with bind parameters and an explicit tenant filter on top of RLS (D7).
 * MANDATORY: each call joins the entry's transaction, so the contact and its lead are stored together or not at all.
 *
 * <p>The new contact is a plain insert, with no {@code returning} and no {@code on conflict}: Postgres checks the new
 * row of either against the select policy, which a staff member's contact passes only once their lead exists. A number
 * stored meanwhile is a unique violation, which becomes {@link PhoneTakenException}.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ManualContactsService implements ManualContacts {

    // The allowlisted function (V202611231210): finds and locks the contact whatever RLS shows the caller.
    private static final String LOCK_EXISTING = """
            select contact_id, has_open_lead from northstar.contact_for_new_lead(?)""";

    private static final String INSERT_CONTACT = """
            insert into northstar.contacts (id, tenant_id, normalized_phone, name, email)
            values (?, ?, ?, ?, ?)""";

    private static final String INSERT_SOURCE = """
            insert into northstar.contact_sources (tenant_id, contact_id, source)
            values (?, ?, ?)
            on conflict on constraint contact_sources_one_per_source do nothing""";

    private static final String FILL_EMPTY_NAME = """
            update northstar.contacts set name = ?
            where tenant_id = ? and id = ? and (name is null or btrim(name) = '')""";

    private static final String FILL_EMPTY_EMAIL = """
            update northstar.contacts set email = ?
            where tenant_id = ? and id = ? and (email is null or btrim(email) = '')""";

    private static final String CONTACT = """
            select id, normalized_phone, name, email, consent_state, opt_out_at
            from northstar.contacts where tenant_id = ? and id = ?""";

    private final JdbcClient jdbc;

    ManualContactsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Existing> lockExisting(String normalizedPhone) {
        requireE164(normalizedPhone);
        memberTenant();
        return jdbc.sql(LOCK_EXISTING)
                .param(normalizedPhone)
                .query((row, n) ->
                        new Existing(row.getObject("contact_id", UUID.class), row.getBoolean("has_open_lead")))
                .optional();
    }

    @Override
    public UUID create(String normalizedPhone, String name, String email) {
        requireE164(normalizedPhone);
        UUID tenant = memberTenant();
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql(INSERT_CONTACT)
                    .param(id)
                    .param(tenant)
                    .param(normalizedPhone)
                    .param(name)
                    .param(email)
                    .update();
        } catch (DuplicateKeyException taken) {
            // The only unique key a fresh random ID can collide on is (tenant_id, normalized_phone).
            throw new PhoneTakenException();
        }
        return id;
    }

    @Override
    public void recordManualEntry(UUID contactId, String name, String email) {
        Objects.requireNonNull(contactId, "contactId");
        UUID tenant = memberTenant();
        jdbc.sql(INSERT_SOURCE)
                .param(tenant)
                .param(contactId)
                .param(ContactSource.MANUAL.code())
                .update();
        if (name != null)
            jdbc.sql(FILL_EMPTY_NAME).param(name).param(tenant).param(contactId).update();
        if (email != null)
            jdbc.sql(FILL_EMPTY_EMAIL)
                    .param(email)
                    .param(tenant)
                    .param(contactId)
                    .update();
    }

    @Override
    public Optional<ContactView> contact(UUID contactId) {
        Objects.requireNonNull(contactId, "contactId");
        return jdbc.sql(CONTACT)
                .param(memberTenant())
                .param(contactId)
                .query((row, n) -> new ContactView(
                        row.getObject("id", UUID.class),
                        row.getString("normalized_phone"),
                        row.getString("name"),
                        row.getString("email"),
                        row.getString("consent_state"),
                        Optional.ofNullable(row.getObject("opt_out_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null)))
                .optional();
    }

    private static void requireE164(String phone) {
        if (!PhoneNumbers.isE164(phone)) throw new IllegalArgumentException("Not an E.164 phone number");
    }

    private static UUID memberTenant() {
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Manual contacts are entered by admins and staff only");
        }
        return context.tenantId();
    }
}
