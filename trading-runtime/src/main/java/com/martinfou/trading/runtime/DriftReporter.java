package com.martinfou.trading.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Story 37-6.
 * Background reporter executing every 12 hours.
 * Validates performance drift of all active strategies and logs DRIFT_ALERT or DRIFT_OK.
 */
public final class DriftReporter {
    private static final Logger log = LoggerFactory.getLogger(DriftReporter.class);

    private final DriftSignalService driftSignalService;
    private final ScheduledExecutorService scheduler;

    public DriftReporter(DriftSignalService driftSignalService) {
        this.driftSignalService = driftSignalService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drift-reporter");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // Schedule every 12 hours, initial delay 60 seconds
        scheduler.scheduleAtFixedRate(this::checkDrift, 60, 12 * 60 * 60, TimeUnit.SECONDS);
        log.info("Drift reporter scheduled to run every 12 hours.");
    }

    public void checkDrift() {
        if (driftSignalService == null) {
            return;
        }
        log.info("Starting scheduled drift validation check...");
        try {
            List<Map<String, Object>> signals = driftSignalService.buildDriftSignals();
            for (Map<String, Object> sig : signals) {
                String strategyId = String.valueOf(sig.get("strategyId"));
                String recommendation = String.valueOf(sig.get("recommendation"));
                String reason = String.valueOf(sig.get("reason"));

                if ("PAUSE".equalsIgnoreCase(recommendation)) {
                    log.warn("[DRIFT_ALERT] Strategy {} has RED drift status: {}", strategyId, reason);
                } else {
                    log.info("[DRIFT_OK] Strategy {} is healthy.", strategyId);
                }
            }
        } catch (Exception e) {
            log.error("Error during scheduled drift verification check: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
