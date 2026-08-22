package com.martinfou.trading.core;

import java.util.Objects;

/**
 * Valuation model for Forex currency pairs (EUR/USD, GBP/USD, USD/JPY, etc.).
 */
public final class ForexValuationModel implements AssetValuationModel {

    private final String symbol;

    public ForexValuationModel(String symbol) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.FOREX;
    }

    @Override
    public double calculatePnL(Order.Side side, double entryPrice, double exitPrice, double quantity, double usdJpyRate) {
        return ForexPnL.pnlUsd(symbol, side, entryPrice, exitPrice, quantity, usdJpyRate);
    }

    @Override
    public double notionalValue(double price, double quantity) {
        return price * quantity;
    }

    @Override
    public double validateQuantity(double quantity) {
        return Math.max(1.0, quantity);
    }
}
