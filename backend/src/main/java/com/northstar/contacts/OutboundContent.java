package com.northstar.contacts;

import java.util.Objects;
import java.util.UUID;

/**
 * What an outbound WhatsApp message says (step 2.6a): free text, or one of the business's templates with its values.
 * Documents, media and interactive buttons come in Phase 5. Text is stored, never logged: toString shows nothing of it.
 */
public sealed interface OutboundContent {

    /** The longest text message, in characters: Meta's limit for a text message body (integration-specs.md section 2). */
    int MAX_TEXT_LENGTH = 4096;

    /** Free text, allowed only inside the 24-hour window when it is sent. */
    record Text(String body) implements OutboundContent {
        public Text {
            Objects.requireNonNull(body, "body");
            if (body.isBlank()) throw new IllegalArgumentException("A text message needs text");
            if (body.codePointCount(0, body.length()) > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("A text message is at most " + MAX_TEXT_LENGTH + " characters");
            }
        }

        @Override
        public String toString() {
            return "OutboundContent.Text[redacted]";
        }
    }

    /** One of the business's templates, with the values it is filled with; sent inside or outside the window. */
    record Template(UUID templateId, TemplateParameters parameters) implements OutboundContent {
        public Template {
            Objects.requireNonNull(templateId, "templateId");
            Objects.requireNonNull(parameters, "parameters");
        }
    }
}
