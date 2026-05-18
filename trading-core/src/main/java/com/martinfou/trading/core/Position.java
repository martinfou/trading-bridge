package com.martinfou.trading.core;

public class Position {
    private final String symbol;
    private final Order.Side side;
    private double quantity;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;

    public Position(String symbol, Order.Side side, double quantity, double entryPrice) {
        this.symbol = symbol; this.side = side;
        this.quantity = quantity; this.entryPrice = entryPrice;
    }

    public double currentPnl(double currentPrice) {
        return side == Order.Side.BUY 
            ? (currentPrice - entryPrice) * quantity 
            : (entryPrice - currentPrice) * quantity;
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
