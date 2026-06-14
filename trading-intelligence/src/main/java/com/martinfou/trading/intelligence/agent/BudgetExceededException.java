package com.martinfou.trading.intelligence.agent;

/**
 * Exception thrown when the accumulated token cost of the ReAct loop exceeds the budget limit.
 */
public class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(String message) {
        super(message);
    }
}
