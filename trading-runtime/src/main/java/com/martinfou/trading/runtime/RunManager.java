package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starts strategy runs asynchronously and persists {@link com.martinfou.trading.backtest.events.RunEvent}
 * records to the {@link EventStore}.
 */
public final class RunManager implements AutoCloseable {

    public record StartRunRequest(
        String strategyId,
        String symbol,
        String mode,
        BarSourceResolver.BarsSource barsSource,
        Double capital
    ) {}

    private static final double DEFAULT_CAPITAL = 100_000.0;

    private final EventStore eventStore;
    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RunManager(EventStore eventStore) {
        if (eventStore == null) {
            throw new IllegalArgumentException("eventStore must not be null");
        }
        this.eventStore = eventStore;
    }

    public String startRun(StartRunRequest request) throws IOException {
        validate(request);
        String runId = UUID.randomUUID().toString();
        RunMode runMode = RunMode.valueOf(request.mode().toUpperCase());
        double capital = request.capital() != null ? request.capital() : DEFAULT_CAPITAL;
        String symbol = request.symbol() != null && !request.symbol().isBlank()
            ? request.symbol()
            : StrategyCatalog.defaultSymbol(request.strategyId());

        List<Bar> bars = BarSourceResolver.load(request.barsSource(), symbol);
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("No bars loaded for run");
        }

        RunRecord record = new RunRecord(runId, request.strategyId(), symbol, runMode);
        runs.put(runId, record);

        executor.submit(() -> executeRun(runId, request.strategyId(), symbol, runMode, bars, capital, record));
        return runId;
    }

    public Optional<RunRecord> getRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public EventStore eventStore() {
        return eventStore;
    }

    @Override
    public void close() {
        executor.close();
    }

    private void executeRun(
        String runId,
        String strategyId,
        String symbol,
        RunMode mode,
        List<Bar> bars,
        double capital,
        RunRecord record
    ) {
        try {
            var context = RunLauncher.create(runId, strategyId, symbol, mode, bars, capital, eventStore);
            BacktestResult result = context.run();
            record.markCompleted(Map.of(
                "totalTrades", result.totalTrades(),
                "totalReturnPct", result.totalReturnPct(),
                "finalEquity", result.finalEquity()));
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            record.markFailed(msg);
        }
    }

    private static void validate(StartRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.strategyId() == null || request.strategyId().isBlank()) {
            throw new IllegalArgumentException("strategyId is required");
        }
        if (!StrategyCatalog.contains(request.strategyId())) {
            throw new IllegalArgumentException("Unknown strategy: " + request.strategyId());
        }
        if (request.mode() == null || request.mode().isBlank()) {
            throw new IllegalArgumentException("mode is required");
        }
        try {
            RunMode.valueOf(request.mode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid mode: " + request.mode());
        }
        RunMode mode = RunMode.valueOf(request.mode().toUpperCase());
        if (mode == RunMode.LIVE) {
            throw new IllegalArgumentException("LIVE mode not supported");
        }
    }
}
