package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Emits {@code HEARTBEAT} run events for liveness / stale detection (Story 13.8, Story 33-2). */
final class HeartbeatEvents {

    private HeartbeatEvents() {}

    static void emitBarHeartbeat(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        EventStore eventStore,
        Bar bar,
        int barIndex
    ) {
        emitBarHeartbeat(runId, config, runMode, eventStore, bar, barIndex, 0, null, 0.0);
    }

    static void emitBarHeartbeat(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        EventStore eventStore,
        Bar bar,
        int barIndex,
        int runningTradeCount,
        Instant lastFillTime,
        double openPnL
    ) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("source", "BAR_LOOP");
        payload.put("barIndex", barIndex);
        payload.put("barTime", bar.timestamp().toString());
        payload.put("runningTradeCount", runningTradeCount);
        payload.put("lastFillTime", lastFillTime != null ? lastFillTime.toString() : com.fasterxml.jackson.databind.node.NullNode.getInstance());
        payload.put("openPnL", openPnL);

        RunEvent event = RunEvent.heartbeat(
            runId,
            config.strategyId(),
            config.symbol(),
            runMode,
            Map.copyOf(payload),
            bar.timestamp());
        eventStore.append(runId, event);
    }
}
