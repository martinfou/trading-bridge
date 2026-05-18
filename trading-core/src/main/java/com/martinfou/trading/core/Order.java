package com.martinfou.trading.core;

import java.time.LocalDateTime;
import java.util.UUID;

public class Order {
    public enum Side { BUY, SELL }
    public enum Type { MARKET, LIMIT, STOP }
    public enum Status { PENDING, FILLED, PARTIAL, CANCELLED, REJECTED }

    private final String id = UUID.randomUUID().toString();
    private final String symbol;
    private final Side side;
    private final Type type;
    private double quantity;
    private double price;
    private double stopLoss;
    private double takeProfit;
    private Status status = Status.PENDING;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime filledAt;

    public Order(String symbol, Side side, Type type, double quantity, double price) {
        this.symbol = symbol; this.side = side; this.type = type;
        this.quantity = quantity; this.price = price;
    }

    // Getters
    public String id() { return id; }
    public String symbol() { return symbol; }
    public Side side() { return side; }
    public Type type() { return type; }
    public double quantity() { return quantity; }
    public double price() { return price; }
    public double stopLoss() { return stopLoss; }
    public double takeProfit() { return takeProfit; }
    public Status status() { return status; }

    public Order withStopLoss(double sl) { this.stopLoss = sl; return this; }
    public Order withTakeProfit(double tp) { this.takeProfit = tp; return this; }
    public Order fill() { this.status = Status.FILLED; this.filledAt = LocalDateTime.now(); return this; }
    
    public double pnl(double currentPrice) {
        if (status != Status.FILLED) return 0;
        return side == Side.BUY ? (currentPrice - price) * quantity : (price - currentPrice) * quantity;
    }
}
