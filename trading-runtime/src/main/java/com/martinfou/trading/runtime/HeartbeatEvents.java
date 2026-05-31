package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.core.Bar;

import java.util.LinkedHashMap;
import java.util.Map;

/** Emits {@code HEARTBEAT} run events for liveness / stale detection (Story 13.8). */
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
        var payload = new LinkedHashMap<String, Object>();
        payload.put("source", "BAR_LOOP");
        payload.put("barIndex", barIndex);
        payload.put("barTime", bar.timestamp().toString());

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
