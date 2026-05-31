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

    record Snapshot(String symbol, Order.Side side, double quantity) {
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
            applyFill(positions, event.payload());
        }
        return Map.copyOf(positions);
    }

    static Map<String, Snapshot> fromBroker(List<Position> positions) {
        Map<String, Snapshot> map = new LinkedHashMap<>();
        for (Position position : positions) {
            Snapshot snapshot = new Snapshot(position.symbol(), position.side(), position.quantity());
            map.put(snapshot.key(), snapshot);
        }
        return Map.copyOf(map);
    }

    private static void applyFill(Map<String, Snapshot> positions, Map<String, Object> payload) {
        Object symbolObj = payload.get("symbol");
        Object sideObj = payload.get("side");
        Object quantityObj = payload.get("quantity");
        if (symbolObj == null || sideObj == null || quantityObj == null) {
            return;
        }
        String symbol = symbolObj.toString();
        Order.Side side = Order.Side.valueOf(sideObj.toString());
        double quantity = ((Number) quantityObj).doubleValue();
        String key = symbol + ":" + side.name();
        Snapshot existing = positions.get(key);
        if (existing == null) {
            positions.put(key, new Snapshot(symbol, side, quantity));
        } else {
            positions.put(key, new Snapshot(symbol, side, existing.quantity() + quantity));
        }
    }
}
