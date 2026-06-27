package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable broker execution event for journaling and tests. */
public record BrokerEvent(
    BrokerEventType type,
    Instant timestamp,
    String orderId,
    String symbol,
    String side,
    Double quantity,
    Double price,
    String message,
    Double stopLoss,
    Double takeProfit,
    String correlationId
) {

    public static BrokerEvent submitted(Order order) {
        return new BrokerEvent(
            BrokerEventType.ORDER_SUBMITTED,
            Instant.now(),
            order.id(),
            order.symbol(),
            order.side().name(),
            order.quantity(),
            order.price(),
            null,
            order.stopLoss(),
            order.takeProfit(),
            order.correlationId());
    }

    public static BrokerEvent fill(Order order, double fillPrice) {
        return fill(order.id(), order.symbol(), order.side().name(), order.quantity(), fillPrice, order.stopLoss(), order.takeProfit(), order.correlationId());
    }

    public static BrokerEvent fill(String orderId, String symbol, String side, double quantity, double fillPrice, Double stopLoss, Double takeProfit) {
        return fill(orderId, symbol, side, quantity, fillPrice, stopLoss, takeProfit, null);
    }

    public static BrokerEvent fill(String orderId, String symbol, String side, double quantity, double fillPrice, Double stopLoss, Double takeProfit, String correlationId) {
        return new BrokerEvent(
            BrokerEventType.FILL,
            Instant.now(),
            orderId,
            symbol,
            side,
            quantity,
            fillPrice,
            null,
            stopLoss,
            takeProfit,
            correlationId);
    }

    public static BrokerEvent reject(Order order, String reason) {
        return reject(order.id(), order.symbol(), order.side().name(), order.quantity(), order.price(), reason, order.stopLoss(), order.takeProfit(), order.correlationId());
    }

    public static BrokerEvent reject(String orderId, String symbol, String side, double quantity, double price, String reason, Double stopLoss, Double takeProfit) {
        return reject(orderId, symbol, side, quantity, price, reason, stopLoss, takeProfit, null);
    }

    public static BrokerEvent reject(String orderId, String symbol, String side, double quantity, double price, String reason, Double stopLoss, Double takeProfit, String correlationId) {
        return new BrokerEvent(
            BrokerEventType.REJECT,
            Instant.now(),
            orderId,
            symbol,
            side,
            quantity,
            price,
            reason,
            stopLoss,
            takeProfit,
            correlationId);
    }

    public static BrokerEvent partialClose(String symbol, String side, double quantity, double price) {
        return new BrokerEvent(
            BrokerEventType.PARTIAL_CLOSE,
            Instant.now(),
            null,
            symbol,
            side,
            quantity,
            price,
            null,
            null,
            null,
            null);
    }

    public static BrokerEvent financing(String symbol, double amount) {
        return new BrokerEvent(
            BrokerEventType.FINANCING,
            Instant.now(),
            null,
            symbol,
            null,
            amount, // We abuse quantity to store financing amount for now
            null,
            "Daily Financing",
            null,
            null,
            null);
    }

    public static BrokerEvent connection(String status, String details) {
        return new BrokerEvent(
            BrokerEventType.CONNECTION,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            status + ":" + details,
            null,
            null,
            null);
    }

    public static BrokerEvent rateLimit(String details) {
        return new BrokerEvent(
            BrokerEventType.RATE_LIMIT,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            details,
            null,
            null,
            null);
    }

    public static BrokerEvent stalePrice(String details) {
        return new BrokerEvent(
            BrokerEventType.STALE_PRICE,
            Instant.now(),
            null,
            null,
            null,
            null,
            null,
            details,
            null,
            null,
            null);
    }


    public Map<String, Object> toPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name());
        map.put("timestamp", timestamp.toString());
        map.put("orderId", orderId);
        map.put("symbol", symbol);
        if (side != null) {
            map.put("side", side);
        }
        if (quantity != null) {
            map.put("quantity", quantity);
        }
        if (price != null) {
            map.put("price", price);
        }
        if (message != null) {
            map.put("message", message);
        }
        if (stopLoss != null) {
            map.put("stopLoss", stopLoss);
        }
        if (takeProfit != null) {
            map.put("takeProfit", takeProfit);
        }
        if (correlationId != null) {
            map.put("correlationId", correlationId);
        }
        return Map.copyOf(map);
    }
}
