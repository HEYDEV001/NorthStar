package com.northstar.contacts;

/**
 * The type an inbound message is stored as ({@code messages.type}, data-model.md section 4). {@code template} is
 * outbound only, so it is not here.
 */
public enum InboundMessageType {
    TEXT,
    INTERACTIVE,
    IMAGE,
    DOCUMENT,
    AUDIO,
    LOCATION,
    UNSUPPORTED;

    /** The value stored in the database, which is also the fixed code an engine event carries. */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
