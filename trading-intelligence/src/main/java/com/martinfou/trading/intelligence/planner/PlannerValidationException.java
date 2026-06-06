package com.martinfou.trading.intelligence.planner;

/** Plan validation failure from planner/reviewer pipeline. */
public final class PlannerValidationException extends Exception {

    private final boolean schemaError;

    public PlannerValidationException(String message, boolean schemaError) {
        super(message);
        this.schemaError = schemaError;
    }

    public boolean schemaError() {
        return schemaError;
    }
}
