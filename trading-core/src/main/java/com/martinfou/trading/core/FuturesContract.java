package com.martinfou.trading.core;

import java.util.Objects;

/**
 * Specification and contract definitions for CME Futures.
 */
public record FuturesContract(
    String symbol,
    String name,
    String exchange,
    String currency,
    double multiplier,
    double minTick,
    double tickValue,
    double initialMargin,
    double maintenanceMargin
) {
    public FuturesContract {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }
        if (multiplier <= 0 || Double.isNaN(multiplier) || Double.isInfinite(multiplier)) {
            throw new IllegalArgumentException("multiplier must be positive and finite");
        }
        if (minTick <= 0 || Double.isNaN(minTick) || Double.isInfinite(minTick)) {
            throw new IllegalArgumentException("minTick must be positive and finite");
        }
    }
}
