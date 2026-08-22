package com.martinfou.trading.core;

import java.time.Instant;
import java.util.UUID;

public record Order(
    String id,
    String symbol,
    Side side,
    Type type,
    double quantity,
    double price,
    double stopLoss,
    double takeProfit,
    double trailingStop,
    boolean guaranteed,
    boolean closeOnly,
    Status status,
    Instant createdAt,
    Instant filledAt,
    String strategyId,
    String correlationId,
    double priceDriftLimit
) {
    public enum Side { BUY, SELL }
    public enum Type { MARKET, LIMIT, STOP }
    public enum Status { PENDING, FILLED, PARTIAL, CANCELLED, REJECTED }

    public Order(String symbol, Side side, Type type, double quantity, double price) {
        this(UUID.randomUUID().toString(), symbol, side, type, quantity, price, 
             0.0, 0.0, 0.0, false, false, Status.PENDING, Instant.now(), 
             null, null, null, 0.0);
    }

    public boolean isCloseOnly() { return closeOnly; }

    public Order withStopLoss(double sl) { return new Order(id, symbol, side, type, quantity, price, sl, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withTakeProfit(double tp) { return new Order(id, symbol, side, type, quantity, price, stopLoss, tp, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withTrailingStop(double ts) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, ts, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withGuaranteed(boolean g) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, g, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withStrategyId(String strategyId) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withCorrelationId(String correlationId) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withPrice(double price) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withFilledAt(Instant filledAt) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withStatus(Status status) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); }
    public Order withPriceDriftLimit(double limit) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, limit); }
    public Order withId(String id) {
        if (id != null && !id.isBlank()) {
            return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit);
        }
        return this;
    }

    public Order rescaleQuantity(double quantity) {
        return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit);
    }

    public Order asCloseOnly() { 
        return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, true, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit); 
    }

    public Order fill() {
        return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, Status.FILLED, createdAt, TimeConventions.now(), strategyId, correlationId, priceDriftLimit);
    }

    public double pnl(double currentPrice) {
        if (status != Status.FILLED) return 0;
        return side == Side.BUY ? (currentPrice - price) * quantity : (price - currentPrice) * quantity;
    }
}
