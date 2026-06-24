package com.martinfou.trading.core.strategy;

/**
 * Record representing a numeric parameter search range for the Grid Search optimizer.
 */
public record ParameterRange(
    String name,
    double min,
    double max,
    double step
) {
    public ParameterRange {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Parameter name cannot be null or empty");
        }
        if (Double.isNaN(min) || Double.isInfinite(min)) {
            throw new IllegalArgumentException("min cannot be NaN or infinite");
        }
        if (Double.isNaN(max) || Double.isInfinite(max)) {
            throw new IllegalArgumentException("max cannot be NaN or infinite");
        }
        if (Double.isNaN(step) || Double.isInfinite(step)) {
            throw new IllegalArgumentException("step cannot be NaN or infinite");
        }
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") cannot be greater than max (" + max + ")");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step must be greater than zero");
        }
    }
}
