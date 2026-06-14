package com.martinfou.trading.intelligence.agent;

/**
 * Exception thrown when the strategist output fails validation rules.
 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
