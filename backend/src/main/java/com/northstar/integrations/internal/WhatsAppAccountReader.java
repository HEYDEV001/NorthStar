package com.northstar.integrations.internal;

import com.northstar.workflow.ActionOutcome;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads what Meta reports about a business's WhatsApp account (integration-specs.md section 2): each number's quality
 * rating and its business portfolio's messaging limit (step 2.8a), and the account's templates, one page at a time
 * (step 2.8b). The provider adapter behind the {@code whatsapp.sync} handler; nothing else calls it. Tests use a fake; {@link CloudApiWhatsAppAccountReader} calls
 * the Graph API.
 *
 * <p>A read changes nothing at Meta, so it is safe to repeat. Its answer is already classified retryable, permanent or
 * auth. Meta's values are returned as sent, for the handler to map; nothing logs them, the token or Meta's IDs.
 */
interface WhatsAppAccountReader {

    /** accountId is Northstar's, for logs only. */
    NumberRead readNumber(UUID accountId, String phoneNumberId, Secret businessToken);

    /**
     * One page of the WABA's templates: the first with {@code after} null, then each next with the cursor the page
     * before returned.
     */
    TemplatePageRead listTemplates(String wabaId, String after, Secret businessToken);

    sealed interface NumberRead {}

    sealed interface TemplatePageRead {}

    /**
     * A page: its templates, how many entries had no usable ID, and the cursor for the next page (null on the last).
     */
    record TemplatePage(List<ListedTemplate> templates, int unreadable, String after) implements TemplatePageRead {
        public TemplatePage {
            templates = List.copyOf(templates);
        }
    }

    /**
     * One template as Meta lists it, values as sent (null: absent or not text): its ID (digits), name, language,
     * status, category, {@code parameter_format} and its BODY component's text. toString shows no name or text.
     */
    record ListedTemplate(
            String id,
            String name,
            String language,
            String status,
            String category,
            String parameterFormat,
            String body) {

        public ListedTemplate {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public String toString() {
            return "ListedTemplate[status=" + status + "]";
        }
    }

    /**
     * Meta's {@code quality_rating} and {@code whatsapp_business_manager_messaging_limit} as sent (null: absent, or not
     * text). limitFieldRefused: this Graph API version refused the limit field, so the number was read without it.
     */
    record NumberHealth(String qualityRating, String messagingLimit, boolean limitFieldRefused) implements NumberRead {}

    record Failed(ActionOutcome.Failed failure) implements NumberRead, TemplatePageRead {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
