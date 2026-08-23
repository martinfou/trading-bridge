package com.martinfou.trading.core;

import java.util.Objects;

/**
 * Valuation model for CME Futures contracts (MES, M2K, EMD, MNQ, etc.),
 * applying contract multipliers and discrete integer contract sizing.
 */
public final class FuturesValuationModel implements AssetValuationModel {

    private final FuturesContract contract;

    public FuturesValuationModel(FuturesContract contract) {
        this.contract = Objects.requireNonNull(contract, "contract");
    }

    public FuturesContract contract() {
        return contract;
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.FUTURES;
    }

    @Override
    public double calculatePnL(Order.Side side, double entryPrice, double exitPrice, double quantity, double usdJpyRate) {
        double contracts = validateQuantity(quantity);
        double multiplier = contract.multiplier();
        double qExit = contract.quantizePrice(exitPrice);
        double priceDiff = side == Order.Side.BUY
            ? (qExit - entryPrice)
            : (entryPrice - qExit);
        return priceDiff * multiplier * contracts;
    }


    @Override
    public double notionalValue(double price, double quantity) {
        double qPrice = contract.quantizePrice(price);
        return qPrice * contract.multiplier() * validateQuantity(quantity);
    }


    @Override
    public double validateQuantity(double quantity) {
        // Futures are strictly discrete contracts (minimum 1 contract)
        return Math.max(1.0, Math.round(quantity));
    }
}
