package com.martinfou.trading.parser.bridge;

import java.io.IOException;

/** Another SQ CLI job holds the mutex (story 21-5). */
public final class SqJobBusyException extends IOException {

    public SqJobBusyException(String message) {
        super(message);
    }
}
