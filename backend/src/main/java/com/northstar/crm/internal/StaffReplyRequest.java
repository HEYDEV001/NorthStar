package com.northstar.crm.internal;

import com.northstar.api.BadRequestException;
import com.northstar.contacts.OutboundContent;
import com.northstar.contacts.TemplateParameters;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A staff member's reply (step 2.9b): free text, or an approved template with its values.
 *
 * <pre>
 * {"type": "text", "text": "..."}
 * {"type": "template", "templateId": "...", "values": ["...", ...]}         positional {{1}} ... {{n}}
 * {"type": "template", "templateId": "...", "namedValues": {"name": "..."}} named {{name}}
 * </pre>
 *
 * A template without variables takes neither. Field rules are Jakarta validation ({@code validation_failed}); a reply
 * whose fields do not fit its type is {@value #REPLY_INVALID}. Text and values are message text: never logged, so
 * toString shows none of it.
 *
 * @param text 1 to {@value OutboundContent#MAX_TEXT_LENGTH} characters (Meta's limit), no U+0000
 */
record StaffReplyRequest(
        @NotNull @Pattern(regexp = "text|template") String type,

        @Size(max = 2 * OutboundContent.MAX_TEXT_LENGTH) @Pattern(regexp = NO_NUL)
        String text,

        UUID templateId,
        @Size(max = MAX_VALUES) List<@NotNull @Pattern(regexp = NO_NUL) String> values,

        @Size(min = 1, max = MAX_VALUES)
        Map<@NotNull @Pattern(regexp = "[a-z_]{1,64}") String, @NotNull @Pattern(regexp = NO_NUL) String> namedValues) {

    /** Postgres text cannot hold U+0000. */
    static final String NO_NUL = "[^\\x00]*";

    static final int MAX_VALUES = 100;

    static final String REPLY_INVALID = "reply_invalid";

    /** What the reply says, once its fields are known to fit its type. */
    OutboundContent toContent() {
        if ("text".equals(type)) {
            if (text == null || text.isBlank() || templateId != null || values != null || namedValues != null) {
                throw invalid("A text reply has its text and nothing else");
            }
            if (text.codePointCount(0, text.length()) > OutboundContent.MAX_TEXT_LENGTH) {
                throw new BadRequestException(
                        "text_too_long", "A text reply is at most " + OutboundContent.MAX_TEXT_LENGTH + " characters");
            }
            return new OutboundContent.Text(text);
        }
        if (templateId == null || text != null || (values != null && namedValues != null)) {
            throw invalid("A template reply has its template and either values or named values");
        }
        TemplateParameters parameters = namedValues != null
                ? TemplateParameters.named(namedValues)
                : new TemplateParameters.Positional(values == null ? List.of() : values);
        return new OutboundContent.Template(templateId, parameters);
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(REPLY_INVALID, message);
    }

    @Override
    public String toString() {
        return "StaffReplyRequest[type=" + type + ", templateId=" + templateId + "]";
    }
}
