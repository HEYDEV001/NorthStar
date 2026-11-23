package com.northstar.crm.internal;

import com.northstar.api.ApiException;
import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.UnprocessableException;
import com.northstar.contacts.ManualContacts;
import com.northstar.contacts.PhoneTakenException;
import com.northstar.crm.internal.LeadResponses.LeadResponse;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.RequestKeys;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.LeadEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manual lead entry (step 2.10, build-document.md section 3; P4, P21, W2): a member enters a phone number, and the
 * contact (if new) and its lead are stored in one transaction, as that member, with RLS. The lead is {@code manual}, in
 * New on the tenant's one published workflow version, assigned to the member (a staff member always; an admin may name
 * any active member), with no conversation until the client writes. Consent stays unknown: entering a number is not an
 * opt-in.
 *
 * <p>In order, in the transaction:
 *
 * <ol>
 *   <li>the request's Idempotency-Key is claimed ({@link RequestKeys}): a repeat of the same request answers with its
 *       lead, the same key with another request is 422;
 *   <li>the published workflow version (else 409 {@code no_published_workflow}), and an admin's assignee locked while
 *       active (else 422 {@code assignee_not_active});
 *   <li>the contact with that number, locked ({@link ManualContacts#lockExisting}): with an open lead, 409
 *       {@code contact_exists}, naming the lead only to someone who can see it (an admin, or a staff member whose own
 *       lead it is); without one (all its leads closed, or none), the new lead is a returning customer's (W2) and an
 *       empty name or email is filled. No contact: a new one;
 *   <li>the lead, the contact's {@code manual} source, and the audit entries {@code lead.created} (and
 *       {@code contact.created} for a new contact).
 * </ol>
 *
 * A number stored by another writer between the lookup and the insert loses the transaction; the entry runs once more
 * and finds the contact. There is no engine event: no {@code staff.action} kind is a lead's creation
 * (workflow-spec.md section 6). Logs carry IDs and codes only, never the phone, name, email or key.
 */
@Service
class ManualLeadEntry {

    private static final Logger log = LoggerFactory.getLogger(ManualLeadEntry.class);

    static final String LEAD_CREATED = "lead.created";
    static final String CONTACT_CREATED = "contact.created";

    private static final String CONTACT_EXISTS_MESSAGE = "A contact with this phone number already has an open lead";

    private final RequestKeys requestKeys;
    private final ManualContacts contacts;
    private final LeadEntry leads;
    private final TenantDirectory tenants;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;

    ManualLeadEntry(
            RequestKeys requestKeys,
            ManualContacts contacts,
            LeadEntry leads,
            TenantDirectory tenants,
            AuditTrail audit,
            PlatformTransactionManager transactionManager) {
        this.requestKeys = requestKeys;
        this.contacts = contacts;
        this.leads = leads;
        this.tenants = tenants;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The lead, and whether this request created it (false: a repeat of an earlier request with the same key). */
    record Entered(LeadResponse lead, boolean created) {}

    /** The request as normalised: what is stored, and what the key's hash covers. */
    private record Entry(Role role, String phone, String name, String email, UUID assignee, String hash) {}

    /** What one attempt stored, for its log line. */
    private record Outcome(LeadResponse lead, boolean created, boolean contactCreated) {}

    Entered create(CreateLeadRequest request, String idempotencyKey) {
        String phone = request.normalizedPhone();
        TenantContext context = TenantScope.require();
        if (context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Manual leads are entered by admins and staff only");
        }
        UUID me = tenants.currentMembershipId()
                .orElseThrow(() -> new ForbiddenException("no_active_membership", "This account has no membership"));
        UUID assignee = assignee(context.role(), me, request.assignedMembershipId());
        String name = request.cleanedName();
        String email = request.emailOrNull();
        Entry entry =
                new Entry(context.role(), phone, name, email, assignee, requestHash(phone, name, email, assignee));

        Outcome outcome;
        boolean retried = false;
        try {
            outcome = transactions.execute(status -> attempt(entry, idempotencyKey));
        } catch (PhoneTakenException raced) {
            // Another writer stored the number after the lookup found none. It is committed now, so this run finds it.
            log.info("lead.create_retried reason=phone_taken_meanwhile");
            retried = true;
            outcome = transactions.execute(status -> attempt(entry, idempotencyKey));
        }
        if (outcome.created()) {
            log.info(
                    "lead.created_manually lead_id={} contact_id={} contact_created={} assignee_membership_id={}"
                            + " retried={}",
                    outcome.lead().id(),
                    outcome.lead().contact().id(),
                    outcome.contactCreated(),
                    outcome.lead().assignedMembershipId(),
                    retried);
        } else {
            log.info("lead.create_repeated lead_id={}", outcome.lead().id());
        }
        return new Entered(outcome.lead(), outcome.created());
    }

    private Outcome attempt(Entry entry, String idempotencyKey) {
        UUID leadId;
        switch (requestKeys.claim(RequestKeys.LEAD_CREATE, idempotencyKey, entry.hash(), UUID.randomUUID())) {
            case RequestKeys.Reused reused -> throw refused(keyReused());
            case RequestKeys.Repeat repeat -> {
                // Its lead may no longer be the caller's to see (reassigned since): the key cannot be used again.
                return view(repeat.targetId())
                        .map(lead -> new Outcome(lead, false, false))
                        .orElseThrow(() -> refused(keyReused()));
            }
            case RequestKeys.Claimed claimed -> leadId = claimed.targetId();
        }

        UUID version = leads.publishedWorkflowVersion()
                .orElseThrow(() -> refused(new ConflictException(
                        "no_published_workflow", "The business has no published workflow to start a lead on")));
        if (entry.role() == Role.ADMIN && !tenants.lockActiveMembership(entry.assignee())) {
            throw refused(new UnprocessableException(
                    "assignee_not_active", "The lead can be assigned only to an active member of the business"));
        }

        Optional<ManualContacts.Existing> existing = contacts.lockExisting(entry.phone());
        UUID contactId;
        if (existing.isEmpty()) {
            contactId = contacts.create(entry.phone(), entry.name(), entry.email());
        } else if (existing.get().hasOpenLead()) {
            throw contactExists(existing.get().contactId());
        } else {
            contactId = existing.get().contactId();
            if (contactId == null) throw new IllegalStateException("A contact without an open lead came with no ID");
        }

        // The contact is locked, and the inbound job takes the same lock, so its open lead cannot appear meanwhile;
        // the one-open-lead index is the backstop.
        if (!leads.open(leadId, contactId, version, entry.assignee())) throw contactExists(contactId);
        contacts.recordManualEntry(contactId, entry.name(), entry.email());
        if (existing.isEmpty()) audit.record(CONTACT_CREATED, "contacts", contactId);
        audit.record(LEAD_CREATED, "leads", leadId);
        LeadResponse lead = view(leadId)
                .orElseThrow(
                        () -> new IllegalStateException("The new lead is not visible to the member who entered it"));
        return new Outcome(lead, true, existing.isEmpty());
    }

    /** The lead and its contact, as the caller may see them. */
    private Optional<LeadResponse> view(UUID leadId) {
        return leads.lead(leadId)
                .flatMap(lead -> contacts.contact(lead.contactId()).map(contact -> LeadResponse.of(lead, contact)));
    }

    /**
     * 409 {@code contact_exists}: with the open lead's ID only when the caller can see that lead (an admin, or a staff
     * member whose own lead it is); otherwise nothing about it (P11). contactId is null when the caller may not learn
     * it.
     */
    private ConflictException contactExists(UUID contactId) {
        Optional<UUID> visible = contactId == null ? Optional.empty() : leads.visibleOpenLead(contactId);
        log.info("lead.create_refused reason=contact_exists lead_visible={}", visible.isPresent());
        return visible.map(
                        lead -> new ConflictException("contact_exists", CONTACT_EXISTS_MESSAGE, Map.of("leadId", lead)))
                .orElseGet(() -> new ConflictException("contact_exists", CONTACT_EXISTS_MESSAGE));
    }

    private static UnprocessableException keyReused() {
        return new UnprocessableException(
                "idempotency_key_reused", "This Idempotency-Key was already used for a different request");
    }

    /** Logs a refusal by its code, and returns it to throw. */
    private static <E extends RuntimeException> E refused(E refusal) {
        String code = refusal instanceof ApiException api ? api.code() : "unknown";
        log.info("lead.create_refused reason={}", code);
        return refusal;
    }

    /**
     * Who the lead is assigned to: a staff member's own membership (naming anyone else is 403
     * {@code assignee_not_allowed}), or for an admin the member named, or themselves.
     */
    private static UUID assignee(Role role, UUID me, UUID requested) {
        if (role == Role.STAFF) {
            if (requested != null && !requested.equals(me)) {
                log.info("lead.create_refused reason=assignee_not_allowed");
                throw new ForbiddenException("assignee_not_allowed", "A staff member's lead is assigned to themselves");
            }
            return me;
        }
        return requested == null ? me : requested;
    }

    /**
     * SHA-256, in lowercase hex, of the request as normalised (so the same number typed two ways is the same request),
     * each field length-prefixed so no two requests share an encoding. Stored with the key, never logged.
     */
    static String requestHash(String phone, String name, String email, UUID assignee) {
        StringBuilder canonical = new StringBuilder(RequestKeys.LEAD_CREATE).append(":v1");
        for (String field : new String[] {phone, name, email, assignee.toString()}) {
            canonical.append('|');
            if (field == null) {
                canonical.append('-');
            } else {
                canonical.append(field.length()).append(':').append(field);
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }
}
