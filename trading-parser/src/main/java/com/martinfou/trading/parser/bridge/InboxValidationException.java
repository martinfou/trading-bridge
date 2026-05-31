package com.martinfou.trading.parser.bridge;

import java.io.IOException;

/** Pre-parse inbox validation failure routed to {@code dlq/} (story 21-3). */
public final class InboxValidationException extends IOException {

    public enum Reason {
        OUTSIDE_PENDING,
        EMPTY_FILE,
        EXCEEDS_MAX_SIZE
    }

    private final Reason reason;

    public InboxValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
