package com.martinfou.trading.core;

/**
 * Immutable definition of a financial trading instrument across Forex, CME Futures, and US Equities/Minicaps.
 */
public record InstrumentDefinition(
    String symbol,
    String name,
    String assetClass,
    double pointValue,
    double tickSize,
    String currency,
    boolean isCustom,
    String providerTicker
) {
    public InstrumentDefinition {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        symbol = symbol.trim().toUpperCase();
        if (name == null || name.isBlank()) {
            name = symbol;
        }
        if (assetClass == null || assetClass.isBlank()) {
            assetClass = "EQUITIES";
        }
        assetClass = assetClass.trim().toUpperCase();
        if (pointValue <= 0) {
            pointValue = 1.0;
        }
        if (tickSize <= 0) {
            tickSize = 0.01;
        }
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
        currency = currency.trim().toUpperCase();
        if (providerTicker == null || providerTicker.isBlank()) {
            providerTicker = symbol;
        }
    }

    public static InstrumentDefinition of(String symbol, String name, String assetClass, double pointValue, double tickSize) {
        return new InstrumentDefinition(symbol, name, assetClass, pointValue, tickSize, "USD", false, symbol);
    }

    public static InstrumentDefinition custom(String symbol, String name, String assetClass, double pointValue, double tickSize) {
        return new InstrumentDefinition(symbol, name, assetClass, pointValue, tickSize, "USD", true, symbol);
    }
}
