package com.martinfou.trading.parser.config;

import java.util.Optional;

/** One strategy parameter from SQ {@code Variables} section. */
public record StrategyParameter(
    String id,
    String name,
    String type,
    String value,
    String paramType
) {
    public Optional<Integer> intValue() {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<Double> doubleValue() {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public boolean booleanValue() {
        return Boolean.parseBoolean(value);
    }
}
