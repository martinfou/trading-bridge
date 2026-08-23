package com.martinfou.trading.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    double priceDriftLimit,
    String ocaGroup,
    int ocaType,
    String parentId
) {
    public enum Side { BUY, SELL }
    public enum Type { MARKET, LIMIT, STOP }
    public enum Status { PENDING, FILLED, PARTIAL, CANCELLED, REJECTED }

    private static final DateTimeFormatter TAG_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        .withZone(ZoneId.of("UTC"));

    public Order(String symbol, Side side, Type type, double quantity, double price) {
        this(UUID.randomUUID().toString(), symbol, side, type, quantity, price, 
             0.0, 0.0, 0.0, false, false, Status.PENDING, Instant.now(), 
             null, null, null, 0.0, null, 0, null);
    }

    public Order(String symbol, Side side, Type type, double quantity, double price, double stopLoss, double takeProfit) {
        this(UUID.randomUUID().toString(), symbol, side, type, quantity, price, 
             stopLoss, takeProfit, 0.0, false, false, Status.PENDING, Instant.now(), 
             null, null, null, 0.0, null, 0, null);
    }

    public Order(
        String id, String symbol, Side side, Type type, double quantity, double price,
        double stopLoss, double takeProfit, double trailingStop, boolean guaranteed,
        boolean closeOnly, Status status, Instant createdAt, Instant filledAt,
        String strategyId, String correlationId, double priceDriftLimit
    ) {
        this(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop,
             guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId,
             priceDriftLimit, null, 0, null);
    }

    /**
     * Generates a deterministic, canonical order tag to enforce idempotency and avoid duplicate submissions.
     */
    public static String generateCanonicalTag(String symbol, String strategy, Instant barTime, Side side) {
        String sym = symbol != null ? symbol.replace("/", "").replace("_", "").toUpperCase() : "UNKNOWN";
        String strat = strategy != null ? strategy.replaceAll("[^a-zA-Z0-9]", "").toUpperCase() : "GENERAL";
        String time = barTime != null ? TAG_FORMATTER.format(barTime) : TAG_FORMATTER.format(Instant.now());
        String s = side != null ? side.name() : "BUY";
        return String.format("%s-%s-%s-%s", sym, strat, time, s);
    }

    public boolean isCloseOnly() { return closeOnly; }

    public Order withStopLoss(double sl) { return new Order(id, symbol, side, type, quantity, price, sl, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withTakeProfit(double tp) { return new Order(id, symbol, side, type, quantity, price, stopLoss, tp, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withTrailingStop(double ts) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, ts, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withGuaranteed(boolean g) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, g, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withStrategyId(String strategyId) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withCorrelationId(String correlationId) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withPrice(double price) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withFilledAt(Instant filledAt) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withStatus(Status status) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }
    public Order withPriceDriftLimit(double limit) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, limit, ocaGroup, ocaType, parentId); }
    public Order withOcaGroup(String group, int ocaTypeVal) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, group, ocaTypeVal, parentId); }
    public Order withParentId(String parentId) { return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); }

    public Order withId(String id) {
        if (id != null && !id.isBlank()) {
            return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId);
        }
        return this;
    }

    public Order rescaleQuantity(double quantity) {
        return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId);
    }

    public Order asCloseOnly() { 
        return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, true, status, createdAt, filledAt, strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId); 
    }

    public Order fill() {
        return new Order(id, symbol, side, type, quantity, price, stopLoss, takeProfit, trailingStop, guaranteed, closeOnly, Status.FILLED, createdAt, TimeConventions.now(), strategyId, correlationId, priceDriftLimit, ocaGroup, ocaType, parentId);
    }

    public double pnl(double currentPrice) {
        if (status != Status.FILLED) return 0;
        return side == Side.BUY ? (currentPrice - price) * quantity : (price - currentPrice) * quantity;
    }
}
