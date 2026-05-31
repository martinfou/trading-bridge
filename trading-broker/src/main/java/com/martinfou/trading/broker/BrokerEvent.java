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
    String message
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
            null);
    }

    public static BrokerEvent fill(Order order, double fillPrice) {
        return new BrokerEvent(
            BrokerEventType.FILL,
            Instant.now(),
            order.id(),
            order.symbol(),
            order.side().name(),
            order.quantity(),
            fillPrice,
            null);
    }

    public static BrokerEvent reject(Order order, String reason) {
        return new BrokerEvent(
            BrokerEventType.REJECT,
            Instant.now(),
            order.id(),
            order.symbol(),
            order.side().name(),
            order.quantity(),
            order.price(),
            reason);
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
        return Map.copyOf(map);
    }
}
