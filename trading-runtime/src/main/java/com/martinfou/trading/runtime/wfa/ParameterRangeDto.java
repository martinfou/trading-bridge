package com.martinfou.trading.runtime.wfa;

/**
 * DTO for parameter range in WFA request.
 */
public record ParameterRangeDto(
    String name,
    double min,
    double max,
    double step
) {
    public ParameterRangeDto {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Parameter name cannot be null or blank");
        }
        if (Double.isNaN(min) || Double.isInfinite(min)) {
            throw new IllegalArgumentException("min must be finite");
        }
        if (Double.isNaN(max) || Double.isInfinite(max)) {
            throw new IllegalArgumentException("max must be finite");
        }
        if (min > max) {
            throw new IllegalArgumentException("min cannot exceed max");
        }
        if (Double.isNaN(step) || Double.isInfinite(step) || step <= 0) {
            throw new IllegalArgumentException("step must be strictly positive and finite");
        }
    }
}
