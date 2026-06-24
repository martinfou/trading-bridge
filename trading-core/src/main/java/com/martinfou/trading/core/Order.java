package com.martinfou.trading.core;

import java.time.Instant;
import java.util.UUID;

public class Order {
    public enum Side { BUY, SELL }
    public enum Type { MARKET, LIMIT, STOP }
    public enum Status { PENDING, FILLED, PARTIAL, CANCELLED, REJECTED }

    private String id = UUID.randomUUID().toString();
    private final String symbol;
    private final Side side;
    private final Type type;
    private double quantity;
    private double price;
    private double stopLoss;
    private double takeProfit;
    private double trailingStop;
    private boolean guaranteed;
    private boolean closeOnly;
    private Status status = Status.PENDING;
    private Instant createdAt = TimeConventions.now();
    private Instant filledAt;
    private String strategyId;
    private String correlationId;
    private double priceDriftLimit;

    public Order(String symbol, Side side, Type type, double quantity, double price) {
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.quantity = quantity;
        this.price = price;
    }

    public String id() { return id; }
    public String symbol() { return symbol; }
    public Side side() { return side; }
    public Type type() { return type; }
    public double quantity() { return quantity; }
    public double price() { return price; }
    public double stopLoss() { return stopLoss; }
    public double takeProfit() { return takeProfit; }
    public double trailingStop() { return trailingStop; }
    public boolean guaranteed() { return guaranteed; }
    public boolean isCloseOnly() { return closeOnly; }
    public Status status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant filledAt() { return filledAt; }
    public String strategyId() { return strategyId; }
    public String correlationId() { return correlationId; }
    public double priceDriftLimit() { return priceDriftLimit; }

    public Order withStopLoss(double sl) { this.stopLoss = sl; return this; }
    public Order withTakeProfit(double tp) { this.takeProfit = tp; return this; }
    public Order withTrailingStop(double ts) { this.trailingStop = ts; return this; }
    public Order withGuaranteed(boolean g) { this.guaranteed = g; return this; }
    public Order withStrategyId(String strategyId) { this.strategyId = strategyId; return this; }
    public Order withCorrelationId(String correlationId) { this.correlationId = correlationId; return this; }
    public Order withPrice(double price) { this.price = price; return this; }
    public Order withFilledAt(Instant filledAt) { this.filledAt = filledAt; return this; }
    public Order withStatus(Status status) { this.status = status; return this; }
    public Order withPriceDriftLimit(double limit) { this.priceDriftLimit = limit; return this; }
    public Order withId(String id) {
        if (id != null && !id.isBlank()) {
            this.id = id;
        }
        return this;
    }

    /** Updates quantity in place so strategy and engine share the same order instance. */
    public Order rescaleQuantity(double quantity) {
        this.quantity = quantity;
        return this;
    }
    /** Mark this order as close-only: reduces existing position instead of opening new one (avoids hedging on OANDA). */
    public Order closeOnly() { this.closeOnly = true; return this; }

    public Order fill() {
        this.status = Status.FILLED;
        this.filledAt = TimeConventions.now();
        return this;
    }

    public double pnl(double currentPrice) {
        if (status != Status.FILLED) return 0;
        return side == Side.BUY ? (currentPrice - price) * quantity : (price - currentPrice) * quantity;
    }
}
