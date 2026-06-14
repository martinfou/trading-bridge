package com.martinfou.trading.intelligence.agent;

/**
 * Exception thrown when the ReAct loop exceeds the maximum number of iterations.
 */
public class IterationLimitExceededException extends RuntimeException {
    public IterationLimitExceededException(String message) {
        super(message);
    }
}
