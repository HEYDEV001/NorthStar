package com.northstar.devtools;

/**
 * A gate tool (step 2.11) that stops on purpose: a fixed code and a sentence for the person running it. The message
 * never carries a value the tool was given (a token, a URL, a key), only names of settings and what to do.
 */
public final class LocalToolException extends RuntimeException {

    private final String code;

    public LocalToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
