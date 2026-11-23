package com.northstar.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Template values (step 2.6a) take the names Meta allows (step 2.9a): lowercase letters and underscores, as Meta's
 * template fundamentals page says (re-checked 22 Nov 2026), and at most 64 characters, the longest placeholder the
 * template reader takes, so a value can always fill a variable read from a template's body and nothing else.
 */
class TemplateParametersTests {

    @Test
    void namedValuesTakeLowercaseLettersAndUnderscoresOnly() {
        assertThat(TemplateParameters.named(Map.of("first_name", "Asha", "_x", "y")))
                .isInstanceOf(TemplateParameters.Named.class);
        assertThat(TemplateParameters.named(Map.of("a".repeat(64), "y"))).isNotNull();

        for (String name : new String[] {"order2", "item_2", "2nd", "First_name", "first-name", "a".repeat(65), ""}) {
            assertThatThrownBy(() -> TemplateParameters.named(Map.of(name, "Asha")))
                    .as(name)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A named parameter is 1 to 64 lowercase letters and underscores");
        }
    }

    @Test
    void theReaderAndTheValuesShareOneRule() {
        assertThat(TemplateParameters.NAMED_PARAMETER.matcher("order_number").matches())
                .isTrue();
        assertThat(TemplateParameters.NAMED_PARAMETER.matcher("order2").matches())
                .isFalse();
        assertThat(TemplateParameters.POSITIONAL_PARAMETER.matcher("1").matches())
                .isTrue();
        assertThat(TemplateParameters.POSITIONAL_PARAMETER.matcher("0").matches())
                .isFalse();
    }
}
