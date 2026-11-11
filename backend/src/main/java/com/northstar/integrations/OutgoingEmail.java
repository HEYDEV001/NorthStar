package com.northstar.integrations;

import java.util.Objects;

/** One plain-text email to one address. Its toString shows nothing, so it can never leak into a log line. */
public record OutgoingEmail(String to, String subject, String text) {

    public OutgoingEmail {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
        return "OutgoingEmail[redacted]";
    }
}
