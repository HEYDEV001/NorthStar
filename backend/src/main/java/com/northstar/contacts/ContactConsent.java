package com.northstar.contacts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A contact's consent to automated messages (step 2.5, W32; workflow-spec.md section 13 Opt-out, data-model.md section
 * 4). Every method runs only inside the caller's transaction (none is opened here), in the current tenant.
 *
 * <p>An opt-out is final until a later step defines opting back in: the database refuses moving a contact away from
 * {@code opted_out} and changing its {@code opt_out_at} once set, and lets only the system actor change consent (trigger
 * {@code contacts_consent_guard}, SQLSTATE NSC01).
 *
 * <p>Message text and keywords are never logged, and no method puts either in an exception message.
 */
public interface ContactConsent {

    /**
     * The platform's opt-out keywords, for every business. {@code stop promotions} (the label of Meta's marketing
     * opt-out button) is a full opt-out until marketing and transactional consent are split (decided 21 Nov 2026).
     */
    List<String> PLATFORM_OPT_OUT_KEYWORDS = List.of("stop", "unsubscribe", "opt out", "optout", "stop promotions");

    /**
     * Whether an inbound message opts its sender out: a text message or an interactive reply whose whole text, once
     * normalised, equals one of {@link #PLATFORM_OPT_OUT_KEYWORDS} or one of the business's own keywords normalised the
     * same way. Normalising applies Unicode NFKC, lowercases, removes invisible format characters, turns punctuation and
     * every kind of white space into a space, makes runs of space one space, and trims. So "Stop!" and "Opt-out." opt
     * out, and "Please don't stop the shoot" does not. Media captions, locations, reactions and every other type never
     * opt out, and text that normalises to nothing never matches.
     */
    boolean isOptOutMessage(InboundMessageType type, String body);

    /**
     * Opts the contact out: {@code consent_state} becomes {@code opted_out} and {@code opt_out_at} is set to
     * {@code optedOutAt} (the WhatsApp timestamp of the message) the first time only, never moved afterwards. Returns
     * true if the contact was not opted out before. System actor only.
     */
    boolean recordOptOut(UUID contactId, Instant optedOutAt);

    /**
     * Whether the contact has opted out, read under a shared row lock held until the caller's transaction ends, so an
     * opt-out being recorded meanwhile (which holds the contact's update lock) is waited for and then seen. A contact the
     * caller cannot see is reported as not found (P9), never as contactable.
     *
     * @throws com.northstar.api.NotFoundException if the contact is not visible in the current tenant
     */
    boolean isOptedOut(UUID contactId);
}
