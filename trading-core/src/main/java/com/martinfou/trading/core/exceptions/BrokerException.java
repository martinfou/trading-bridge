package com.martinfou.trading.core.exceptions;

/**
 * Base exception class for all broker-related errors.
 */
public class BrokerException extends RuntimeException {
    public BrokerException(String message) {
        super(message);
    }

    public BrokerException(String message, Throwable cause) {
        super(message, cause);
    }
}
