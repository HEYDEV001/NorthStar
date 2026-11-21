package com.northstar.integrations.internal;

import com.northstar.workflow.ActionOutcome;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads what Meta reports about one of a business's WhatsApp numbers (step 2.8a, integration-specs.md section 2,
 * Quality and limits): its quality rating and its business portfolio's messaging limit. The provider adapter behind the
 * {@code whatsapp.sync} handler; nothing else calls it. Tests use a fake; {@link CloudApiWhatsAppAccountReader} calls
 * the Graph API.
 *
 * <p>A read changes nothing at Meta, so it is safe to repeat. Its answer is already classified retryable, permanent or
 * auth. Meta's values are returned as sent, for the handler to map; nothing logs them, the token or Meta's IDs.
 */
interface WhatsAppAccountReader {

    /** accountId is Northstar's, for logs only. */
    NumberRead readNumber(UUID accountId, String phoneNumberId, Secret businessToken);

    sealed interface NumberRead {}

    /**
     * Meta's {@code quality_rating} and {@code whatsapp_business_manager_messaging_limit} as sent (null: absent, or not
     * text). limitFieldRefused: this Graph API version refused the limit field, so the number was read without it.
     */
    record NumberHealth(String qualityRating, String messagingLimit, boolean limitFieldRefused) implements NumberRead {}

    record Failed(ActionOutcome.Failed failure) implements NumberRead {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
