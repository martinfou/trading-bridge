package com.martinfou.trading.backtest.persistence;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.core.ForexPnL;

import java.time.Instant;
import java.util.*;

public final class TradeReconstructor {

    private TradeReconstructor() {}

    private static double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static List<Trade> reconstruct(List<RunEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        // 1. Filter and extract fill events
        List<RawFill> rawFills = new ArrayList<>();
        for (RunEvent event : events) {
            if (event == null) {
                continue;
            }
            if (event.type() != RunEventType.FILL) {
                continue;
            }
            Map<String, Object> payload = event.payload();
            if (payload == null) {
                continue;
            }

            // Filter out FINANCING and other non-trade fills
            Object typeObj = payload.get("type");
            if (typeObj != null && "FINANCING".equalsIgnoreCase(typeObj.toString())) {
                continue;
            }

            Object sideObj = payload.get("side");
            if (sideObj == null || "null".equalsIgnoreCase(sideObj.toString())) {
                continue;
            }

            String symbol = event.symbol();
            if (symbol == null || symbol.isBlank()) {
                Object symObj = payload.get("symbol");
                symbol = symObj != null ? symObj.toString() : null;
            }
            if (symbol == null || symbol.isBlank() || "null".equalsIgnoreCase(symbol)) {
                continue;
            }

            Order.Side side;
            try {
                side = Order.Side.valueOf(sideObj.toString().toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            double quantity = toDouble(payload.get("quantity"));
            double price = toDouble(payload.get("price"));
            Instant timestamp = event.timestamp();
            double stopLoss = toDouble(payload.get("stopLoss"));
            double takeProfit = toDouble(payload.get("takeProfit"));
            String orderId = payload.containsKey("orderId") && payload.get("orderId") != null ? String.valueOf(payload.get("orderId")) : "";
            String correlationId = payload.containsKey("correlationId") && payload.get("correlationId") != null ? String.valueOf(payload.get("correlationId")) : "";

            rawFills.add(new RawFill(symbol, side, quantity, price, timestamp, stopLoss, takeProfit, orderId, correlationId));
        }

        // 2. Group/merge partial fills
        List<RawFill> mergedFills = mergePartialFills(rawFills);

        // 3. FIFO Matching
        List<Trade> trades = new ArrayList<>();
        Map<String, List<RawFill>> openFillsBySymbol = new HashMap<>();

        for (RawFill fill : mergedFills) {
            String symbol = fill.symbol;
            List<RawFill> openQueue = openFillsBySymbol.computeIfAbsent(symbol, k -> new ArrayList<>());

            double remainingQty = fill.quantity;
            while (remainingQty > 1e-9 && !openQueue.isEmpty() && openQueue.get(0).side != fill.side) {
                RawFill openFill = openQueue.get(0);
                double matchQty = Math.min(remainingQty, openFill.quantity);

                double entryPrice = openFill.price;
                double exitPrice = fill.price;
                Order.Side entrySide = openFill.side;
                Instant entryTime = openFill.timestamp;
                Instant exitTime = fill.timestamp;

                Trade trade = new Trade(
                    symbol,
                    entrySide,
                    entryPrice,
                    exitPrice,
                    matchQty,
                    entryTime,
                    exitTime,
                    ForexPnL.DEFAULT_USD_JPY,
                    openFill.stopLoss,
                    openFill.takeProfit
                );
                trades.add(trade);

                remainingQty -= matchQty;
                openFill.quantity -= matchQty;
                if (openFill.quantity <= 1e-9) {
                    openQueue.remove(0);
                }
            }

            if (remainingQty > 1e-9) {
                fill.quantity = remainingQty;
                openQueue.add(fill);
            }
        }

        return List.copyOf(trades);
    }

    private static List<RawFill> mergePartialFills(List<RawFill> fills) {
        List<RawFill> merged = new ArrayList<>();
        Map<String, RawFill> mergeMap = new HashMap<>(); // key is orderId/correlationId + side + symbol

        for (RawFill fill : fills) {
            String key = "";
            if (fill.orderId != null && !fill.orderId.isBlank()) {
                key = fill.orderId + ":" + fill.side.name() + ":" + fill.symbol;
            } else if (fill.correlationId != null && !fill.correlationId.isBlank()) {
                key = fill.correlationId + ":" + fill.side.name() + ":" + fill.symbol;
            }

            if (key.isEmpty()) {
                merged.add(fill);
            } else {
                RawFill existing = mergeMap.get(key);
                if (existing != null) {
                    double totalQty = existing.quantity + fill.quantity;
                    if (totalQty > 0.0) {
                        existing.price = (existing.price * existing.quantity + fill.price * fill.quantity) / totalQty;
                        existing.quantity = totalQty;
                    }
                } else {
                    RawFill copy = new RawFill(
                        fill.symbol, fill.side, fill.quantity, fill.price, fill.timestamp,
                        fill.stopLoss, fill.takeProfit, fill.orderId, fill.correlationId
                    );
                    mergeMap.put(key, copy);
                    merged.add(copy);
                }
            }
        }
        return merged;
    }

    private static class RawFill {
        final String symbol;
        final Order.Side side;
        double quantity;
        double price;
        final Instant timestamp;
        final double stopLoss;
        final double takeProfit;
        final String orderId;
        final String correlationId;

        RawFill(String symbol, Order.Side side, double quantity, double price, Instant timestamp,
                double stopLoss, double takeProfit, String orderId, String correlationId) {
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.price = price;
            this.timestamp = timestamp;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.orderId = orderId;
            this.correlationId = correlationId;
        }
    }
}
