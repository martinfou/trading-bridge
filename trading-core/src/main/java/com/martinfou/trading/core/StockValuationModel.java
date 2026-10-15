package com.martinfou.trading.core;

import java.util.Objects;

/**
 * Valuation model for US Equities / Stocks (e.g. IWM, MDY, AAPL, SPY, QQQ).
 * Multiplier is 1.0, quantity is discrete shares (minimum 1 share).
 */
public final class StockValuationModel implements AssetValuationModel {

    private final String symbol;

    public StockValuationModel(String symbol) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
    }

    public String symbol() {
        return symbol;
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.EQUITY;
    }

    @Override
    public double calculatePnL(Order.Side side, double entryPrice, double exitPrice, double quantity, double usdJpyRate) {
        double shares = validateQuantity(quantity);
        double priceDiff = side == Order.Side.BUY
            ? (exitPrice - entryPrice)
            : (entryPrice - exitPrice);
        return priceDiff * 1.0 * shares;
    }

    @Override
    public double notionalValue(double price, double quantity) {
        return price * 1.0 * validateQuantity(quantity);
    }

    @Override
    public double validateQuantity(double quantity) {
        // Equities discrete whole shares
        return Math.max(1.0, Math.round(quantity));
    }
}
