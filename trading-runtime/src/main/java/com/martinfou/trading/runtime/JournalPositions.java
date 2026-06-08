package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Positions derived from journaled FILL events (Story 16.7). */
final class JournalPositions {

    record Snapshot(String symbol, Order.Side side, double quantity, java.time.Instant entryTime) {
        String key() {
            return symbol + ":" + side.name();
        }
    }

    private JournalPositions() {}

    static Map<String, Snapshot> fromFills(List<RunEvent> events) {
        Map<String, Snapshot> positions = new LinkedHashMap<>();
        for (RunEvent event : events) {
            if (event.type() != RunEventType.FILL) {
                continue;
            }
            applyFill(positions, event.payload(), event.timestamp());
        }
        return Map.copyOf(positions);
    }

    static Map<String, Snapshot> fromBroker(List<Position> positions) {
        Map<String, Snapshot> map = new LinkedHashMap<>();
        for (Position position : positions) {
            Snapshot snapshot = new Snapshot(position.symbol(), position.side(), position.quantity(), position.entryTime());
            map.put(snapshot.key(), snapshot);
        }
        return Map.copyOf(map);
    }

    private static void applyFill(Map<String, Snapshot> positions, Map<String, Object> payload, java.time.Instant timestamp) {
        Object symbolObj = payload.get("symbol");
        Object sideObj = payload.get("side");
        Object quantityObj = payload.get("quantity");
        if (symbolObj == null || sideObj == null || quantityObj == null) {
            return;
        }
        String symbol = symbolObj.toString();
        Order.Side side = Order.Side.valueOf(sideObj.toString());
        double quantity = ((Number) quantityObj).doubleValue();

        String buyKey = symbol + ":" + Order.Side.BUY.name();
        String sellKey = symbol + ":" + Order.Side.SELL.name();

        double oldNetQty = 0.0;
        java.time.Instant existingEntryTime = null;
        Snapshot buyExisting = positions.get(buyKey);
        Snapshot sellExisting = positions.get(sellKey);

        if (buyExisting != null) {
            oldNetQty += buyExisting.quantity();
            existingEntryTime = buyExisting.entryTime();
        }
        if (sellExisting != null) {
            oldNetQty -= sellExisting.quantity();
            existingEntryTime = sellExisting.entryTime();
        }

        double netQty = oldNetQty;
        if (side == Order.Side.BUY) {
            netQty += quantity;
        } else {
            netQty -= quantity;
        }

        positions.remove(buyKey);
        positions.remove(sellKey);

        if (Math.abs(netQty) > 1e-9) {
            java.time.Instant entryTime = timestamp;
            if (Math.abs(oldNetQty) > 1e-9 && (oldNetQty * netQty > 0)) {
                entryTime = existingEntryTime;
            }
            if (netQty > 0) {
                positions.put(buyKey, new Snapshot(symbol, Order.Side.BUY, netQty, entryTime));
            } else {
                positions.put(sellKey, new Snapshot(symbol, Order.Side.SELL, Math.abs(netQty), entryTime));
            }
        }
    }
}
