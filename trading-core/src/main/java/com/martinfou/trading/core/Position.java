package com.martinfou.trading.core;

public record Position(
    String symbol,
    Order.Side side,
    double quantity,
    double entryPrice,
    java.time.Instant entryTime,
    String clientTag,
    String brokerTradeId,
    double stopLoss,
    double takeProfit
) {
    public Position(String symbol, Order.Side side, double quantity, double entryPrice, java.time.Instant entryTime, String clientTag, String brokerTradeId) {
        this(symbol, side, quantity, entryPrice, entryTime, clientTag, brokerTradeId, 0.0, 0.0);
    }

    public Position(String symbol, Order.Side side, double quantity, double entryPrice, java.time.Instant entryTime, String clientTag) {
        this(symbol, side, quantity, entryPrice, entryTime, clientTag, null);
    }

    public Position(String symbol, Order.Side side, double quantity, double entryPrice, java.time.Instant entryTime) {
        this(symbol, side, quantity, entryPrice, entryTime, null);
    }

    public Position(String symbol, Order.Side side, double quantity, double entryPrice) {
        this(symbol, side, quantity, entryPrice, java.time.Instant.EPOCH, null);
    }

    public double currentPnl(double currentPrice) {
        return currentPnl(currentPrice, ForexPnL.DEFAULT_USD_JPY);
    }

    public double currentPnl(double currentPrice, double usdJpyRate) {
        return ForexPnL.pnlUsd(symbol, side, entryPrice, currentPrice, quantity, usdJpyRate);
    }

    public double pnlPercent(double currentPrice) {
        return side == Order.Side.BUY 
            ? (currentPrice - entryPrice) / entryPrice * 100
            : (entryPrice - currentPrice) / entryPrice * 100;
    }

    public Position withStopLoss(double sl) { 
        return new Position(symbol, side, quantity, entryPrice, entryTime, clientTag, brokerTradeId, sl, takeProfit);
    }
    
    public Position withTakeProfit(double tp) { 
        return new Position(symbol, side, quantity, entryPrice, entryTime, clientTag, brokerTradeId, stopLoss, tp);
    }

    public Position addQuantity(double qty, double avgPrice) {
        double totalQty = this.quantity + qty;
        double newEntryPrice = (this.entryPrice * this.quantity + avgPrice * qty) / totalQty;
        return new Position(symbol, side, totalQty, newEntryPrice, entryTime, clientTag, brokerTradeId, stopLoss, takeProfit);
    }

    public Position reduceQuantity(double qty) {
        double newQty = this.quantity - (qty > this.quantity ? this.quantity : qty);
        return new Position(symbol, side, newQty, entryPrice, entryTime, clientTag, brokerTradeId, stopLoss, takeProfit);
    }

    @Override
    public String toString() { 
        return String.format("%s %s %.4f@%.5f", symbol, side, quantity, entryPrice); 
    }
}
