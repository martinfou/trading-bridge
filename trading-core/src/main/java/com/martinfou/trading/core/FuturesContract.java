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

    /**
     * Snaps a raw price to the nearest valid exchange tick boundary.
     *
     * @param rawPrice unquantized price (e.g. from indicators or floating math)
     * @return quantized price rounded to nearest minTick
     */
    public double quantizePrice(double rawPrice) {
        if (Double.isNaN(rawPrice) || Double.isInfinite(rawPrice)) {
            return rawPrice;
        }
        double ticks = Math.round(rawPrice / minTick);
        return Math.round(ticks * minTick * 1_000_000.0) / 1_000_000.0;
    }

    /**
     * Checks if a price is exactly on a valid tick boundary.
     */
    public boolean isValidTick(double price) {
        if (Double.isNaN(price) || Double.isInfinite(price)) {
            return false;
        }
        return Math.abs(price - quantizePrice(price)) < 1e-6;
    }

    /**
     * Calculates the signed number of ticks between two prices.
     */
    public long tickDifference(double price1, double price2) {
        return Math.round((price2 - price1) / minTick);
    }
}

