package com.martinfou.trading.core;

import java.time.Instant;
import java.util.UUID;

public class Trade {
    private final String id;
    private final String symbol;
    private final Order.Side side;
    private final double entryPrice, exitPrice;
    private final double quantity;
    private final Instant entryTime;
    private final Instant exitTime;
    private final double pnl;
    private final double stopLoss;
    private final double takeProfit;
    private final String rolloverGroupId;

    public Trade(String id, String symbol, Order.Side side, double entryPrice, double exitPrice,
                 double quantity, Instant entryTime, Instant exitTime, double pnl,
                 double stopLoss, double takeProfit, String rolloverGroupId) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.quantity = quantity;
        this.entryTime = entryTime;
        this.exitTime = exitTime;
        this.pnl = pnl;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.rolloverGroupId = rolloverGroupId;
    }

    public Trade(String id, String symbol, Order.Side side, double entryPrice, double exitPrice,
                 double quantity, Instant entryTime, Instant exitTime, double pnl,
                 double stopLoss, double takeProfit) {
        this(id, symbol, side, entryPrice, exitPrice, quantity, entryTime, exitTime, pnl, stopLoss, takeProfit, null);
    }

    public Trade(String symbol, Order.Side side, double entryPrice, double exitPrice,
                 double quantity, Instant entryTime, Instant exitTime) {
        this(UUID.randomUUID().toString(), symbol, side, entryPrice, exitPrice, quantity, entryTime, exitTime,
             AssetValuationRegistry.calculatePnL(symbol, side, entryPrice, exitPrice, quantity, ForexPnL.DEFAULT_USD_JPY), 0.0, 0.0, null);
    }

    public Trade(String symbol, Order.Side side, double entryPrice, double exitPrice,
                 double quantity, Instant entryTime, Instant exitTime, double usdJpyRate) {
        this(UUID.randomUUID().toString(), symbol, side, entryPrice, exitPrice, quantity, entryTime, exitTime,
             AssetValuationRegistry.calculatePnL(symbol, side, entryPrice, exitPrice, quantity, usdJpyRate), 0.0, 0.0, null);
    }

    public Trade(String symbol, Order.Side side, double entryPrice, double exitPrice,
                 double quantity, Instant entryTime, Instant exitTime, double usdJpyRate,
                 double stopLoss, double takeProfit) {
        this(UUID.randomUUID().toString(), symbol, side, entryPrice, exitPrice, quantity, entryTime, exitTime,
             AssetValuationRegistry.calculatePnL(symbol, side, entryPrice, exitPrice, quantity, usdJpyRate), stopLoss, takeProfit, null);
    }

    public Trade(String symbol, Order.Side side, double entryPrice, double exitPrice,
                 double quantity, Instant entryTime, Instant exitTime, double usdJpyRate,
                 double stopLoss, double takeProfit, String rolloverGroupId) {
        this(UUID.randomUUID().toString(), symbol, side, entryPrice, exitPrice, quantity, entryTime, exitTime,
             AssetValuationRegistry.calculatePnL(symbol, side, entryPrice, exitPrice, quantity, usdJpyRate), stopLoss, takeProfit, rolloverGroupId);
    }

    public String id() { return id; }
    public double pnl() { return pnl; }
    public double pnlPercent() { return (pnl / (entryPrice * quantity)) * 100; }
    public Instant entryTime() { return entryTime; }
    public Instant exitTime() { return exitTime; }
    public String symbol() { return symbol; }
    public Order.Side side() { return side; }
    public double entryPrice() { return entryPrice; }
    public double exitPrice() { return exitPrice; }
    public double quantity() { return quantity; }
    public double stopLoss() { return stopLoss; }
    public double takeProfit() { return takeProfit; }
    public String rolloverGroupId() { return rolloverGroupId; }

    @Override
    public String toString() {
        return String.format("%s %s %.4f->%.5f PnL:%.2f", symbol, side, entryPrice, exitPrice, pnl);
    }
}
