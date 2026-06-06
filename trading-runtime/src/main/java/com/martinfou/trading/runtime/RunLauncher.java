package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Builds {@link RunContext} with catalog resolution (composition layer). */
final class RunLauncher {

    private RunLauncher() {}

    static RunContext create(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double capital,
        RunConfigSnapshot configSnapshot,
        EventStore eventStore
    ) {
        Map<String, Object> startedExtras = new LinkedHashMap<>();
        startedExtras.put("configSnapshot", configSnapshot.toMap());
        startedExtras.put("configHash", configSnapshot.hash());
        startedExtras.put("executionLabel", configSnapshot.resolvedExecutionLabel().name());

        Consumer<RunEvent> listener = event -> {
            RunEvent persisted = enrichStartedEvent(runId, event, startedExtras);
            eventStore.append(runId, persisted);
        };

        return RunContext.forStrategy(
            runId,
            strategyId,
            StrategyCatalog.create(strategyId, symbol, configSnapshot.quantity()),
            symbol,
            mode,
            bars,
            capital,
            listener,
            configSnapshot.executionCost());
    }

    private static RunEvent enrichStartedEvent(
        String runId,
        RunEvent event,
        Map<String, Object> startedExtras
    ) {
        if (event.type() != RunEventType.RUN_STARTED) {
            return withRunId(runId, event);
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.putAll(startedExtras);
        return new RunEvent(
            event.schemaVersion(),
            event.type(),
            event.timestamp(),
            runId,
            event.strategyId(),
            event.symbol(),
            event.mode(),
            Map.copyOf(payload));
    }

    private static RunEvent withRunId(String runId, RunEvent event) {
        if (runId.equals(event.runId())) {
            return event;
        }
        return new RunEvent(
            event.schemaVersion(),
            event.type(),
            event.timestamp(),
            runId,
            event.strategyId(),
            event.symbol(),
            event.mode(),
            event.payload());
    }
}
