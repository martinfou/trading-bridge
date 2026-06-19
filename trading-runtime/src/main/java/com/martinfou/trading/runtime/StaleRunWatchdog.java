package com.martinfou.trading.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the ControlSummaryService for stale runs and automatically restarts them.
 * Includes throttling to prevent endless crash loops.
 */
public class StaleRunWatchdog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StaleRunWatchdog.class);

    private final RunManager runManager;
    private final ControlSummaryService summaryService;
    private final ScheduledExecutorService executor;
    private final Map<String, Integer> restartsPerHour = new ConcurrentHashMap<>();
    private Instant currentHour = Instant.now();

    public StaleRunWatchdog(RunManager runManager, ControlSummaryService summaryService) {
        this.runManager = runManager;
        this.summaryService = summaryService;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stale-run-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        executor.scheduleAtFixedRate(this::checkStaleRuns, 30, 60, TimeUnit.SECONDS);
        log.info("StaleRunWatchdog started. Checking for stale runs every 60 seconds.");
    }

    void checkStaleRuns() {
        try {
            if (Duration.between(currentHour, Instant.now()).toHours() >= 1) {
                restartsPerHour.clear();
                currentHour = Instant.now();
            }

            Map<String, Object> summary = summaryService.buildSummary();
            if (summary == null) return;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> signals = (Map<String, Object>) summary.get("signals");
            if (signals == null) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> staleSignals = (List<Map<String, Object>>) signals.get("stale");
            if (staleSignals == null || staleSignals.isEmpty()) return;

            for (Map<String, Object> signal : staleSignals) {
                String runId = (String) signal.get("runId");
                String status = (String) signal.get("status");

                if (!"RUNNING".equals(status)) {
                    continue;
                }

                String strategyId = (String) signal.get("strategyId");
                int restarts = restartsPerHour.getOrDefault(strategyId, 0);

                if (restarts >= 3) {
                    log.warn("Strategy {} has reached the maximum auto-restart limit (3 per hour). Skipping restart for stale run {}.", strategyId, runId);
                    continue;
                }

                log.warn("Run {} for strategy {} is STALE. Initiating Watchdog recovery...", runId, strategyId);
                
                try {
                    // 1. Get original config before killing
                    var recordOpt = runManager.getRun(runId);
                    if (recordOpt.isEmpty()) {
                        continue;
                    }
                    RunRecord record = recordOpt.get();
                    RunConfigSnapshot config = RunConfigSnapshot.fromRecord(record);

                    // 2. Stop the dead run
                    runManager.stop(runId);
                    
                    // 3. Register and start new run
                    RunRecord newRecord = runManager.register(config);
                    runManager.start(newRecord.runId());

                    restartsPerHour.put(strategyId, restarts + 1);
                    log.info("Successfully restarted strategy {} with new run ID: {}", strategyId, newRecord.runId());
                } catch (Exception e) {
                    log.error("Failed to auto-restart stale run {}: {}", runId, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error during stale run watchdog check", e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
