package com.martinfou.trading.core;

/**
 * Interface defining asset valuation, P&amp;L calculation, and notional conversion
 * across different asset classes (Forex, Futures, US Equities).
 */
public interface AssetValuationModel {

    AssetClass assetClass();

    double calculatePnL(Order.Side side, double entryPrice, double exitPrice, double quantity, double usdJpyRate);

    double notionalValue(double price, double quantity);

    double validateQuantity(double quantity);
}
