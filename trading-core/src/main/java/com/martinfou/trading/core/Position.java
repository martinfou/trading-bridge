package com.martinfou.trading.core;

public class Position {
    private final String symbol;
    private final Order.Side side;
    private double quantity;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;

    private final java.time.Instant entryTime;
    private final String clientTag;
    private String brokerTradeId;

    public String brokerTradeId() { return brokerTradeId; }
    public Position withBrokerTradeId(String brokerTradeId) { this.brokerTradeId = brokerTradeId; return this; }

    public Position(String symbol, Order.Side side, double quantity, double entryPrice, java.time.Instant entryTime, String clientTag) {
        this.symbol = symbol; this.side = side;
        this.quantity = quantity; this.entryPrice = entryPrice;
        this.entryTime = entryTime;
        this.clientTag = clientTag;
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

    // Getters
    public String symbol() { return symbol; }
    public Order.Side side() { return side; }
    public double quantity() { return quantity; }
    public double entryPrice() { return entryPrice; }
    public double stopLoss() { return stopLoss; }
    public double takeProfit() { return takeProfit; }
    public java.time.Instant entryTime() { return entryTime; }
    public String clientTag() { return clientTag; }
    public Position withStopLoss(double sl) { this.stopLoss = sl; return this; }
    public Position withTakeProfit(double tp) { this.takeProfit = tp; return this; }

    public void addQuantity(double qty, double avgPrice) {
        double totalQty = this.quantity + qty;
        this.entryPrice = (this.entryPrice * this.quantity + avgPrice * qty) / totalQty;
        this.quantity = totalQty;
    }

    public double reduceQuantity(double qty) {
        if (qty > quantity) qty = quantity;
        this.quantity -= qty;
        return qty;
    }

    @Override
    public String toString() { return String.format("%s %s %.4f@%.5f", symbol, side, quantity, entryPrice); }
}
