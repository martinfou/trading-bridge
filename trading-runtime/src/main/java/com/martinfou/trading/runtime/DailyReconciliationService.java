package com.martinfou.trading.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.backtest.events.RunEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a daily task to check database integrity, reconcile events vs trades,
 * monitor database size, and export a daily report (Epic 36 story 4).
 */
public final class DailyReconciliationService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DailyReconciliationService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RunManager runManager;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;

    public DailyReconciliationService(RunManager runManager) {
        this(runManager, Clock.systemUTC());
    }

    DailyReconciliationService(RunManager runManager, Clock clock) {
        this.runManager = runManager;
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-reconciliation");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        long initialDelay = computeDelayToNextMidnight();
        scheduler.scheduleAtFixedRate(this::runReconciliation, initialDelay, 24 * 60 * 60, TimeUnit.SECONDS);
        log.info("Daily reconciliation scheduler initialized. Next run in {} seconds.", initialDelay);
    }

    public Map<String, Object> runReconciliation() {
        log.info("Triggering scheduled daily database and runs reconciliation check...");
        Map<String, Object> report = new HashMap<>();
        List<String> warnings = new ArrayList<>();

        long dbSizeBytes = 0;
        try {
            Path dbPath = RuntimeDataPaths.defaultEventStorePath();
            if (Files.exists(dbPath)) {
                dbSizeBytes = Files.size(dbPath);
            }
        } catch (IOException e) {
            log.warn("Failed to read database file size: {}", e.getMessage());
        }

        int totalRuns = 0;
        int completedRuns = 0;
        int activeRuns = 0;
        int failedRuns = 0;

        try {
            RunRecordStore store = runManager.runRecordStore();
            List<RunRecord> allRecords = store.listAll();
            totalRuns = allRecords.size();

            for (RunRecord record : allRecords) {
                switch (record.status()) {
                    case COMPLETED -> {
                        completedRuns++;
                        String runId = record.runId();
                        long fillCount = runManager.eventStore().replayAll(runId).stream()
                            .filter(e -> e.type() == RunEventType.FILL)
                            .count();
                        long tradeCount = runManager.tradeStore().getTrades(runId).size();
                        if (fillCount != 2 * tradeCount) {
                            String msg = String.format("Run %s (%s) has FILL events (%d) vs trades count (%d) mismatch. Expected %d fills.",
                                runId, record.strategyId(), fillCount, tradeCount, 2 * tradeCount);
                            warnings.add(msg);
                            log.warn("Reconciliation mismatch: {}", msg);
                        }
                    }
                    case RUNNING, PAUSED -> activeRuns++;
                    case FAILED -> failedRuns++;
                    default -> {}
                }
            }
        } catch (Exception e) {
            log.error("Failed to retrieve runs for reconciliation", e);
            warnings.add("Failed to retrieve runs: " + e.getMessage());
        }

        int prunedEvents = 0;
        try {
            SqliteEventStore sqliteStore = findSqliteStore(runManager.eventStore());
            if (sqliteStore != null) {
                prunedEvents = sqliteStore.pruneEventsOlderThanDays(30);
            }
        } catch (Exception e) {
            log.error("Failed to prune old events", e);
        }

        report.put("timestamp", ZonedDateTime.now(clock.getZone()).toString());
        report.put("databaseSizeBytes", dbSizeBytes);
        report.put("totalRuns", totalRuns);
        report.put("completedRuns", completedRuns);
        report.put("activeRuns", activeRuns);
        report.put("failedRuns", failedRuns);
        report.put("prunedEventsCount", prunedEvents);
        report.put("warnings", warnings);
        report.put("status", warnings.isEmpty() ? "OK" : "MISMATCH");

        // Write report summary file to disk
        try {
            Path reportPath = RuntimeDataPaths.defaultDataDirectory().resolve("reconciliation-report.json");
            Files.createDirectories(reportPath.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            Files.writeString(reportPath, json);
            log.info("Daily reconciliation report successfully saved to: {}", reportPath);
        } catch (IOException e) {
            log.error("Failed to write daily reconciliation report to disk", e);
        }

        return report;
    }

    private long computeDelayToNextMidnight() {
        ZonedDateTime now = ZonedDateTime.now(clock.getZone());
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(clock.getZone());
        return Duration.between(now, nextMidnight).toSeconds();
    }

    @Override
    public void close() {
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

    private SqliteEventStore findSqliteStore(Object store) {
        Object current = store;
        while (current != null) {
            if (current instanceof SqliteEventStore) {
                return (SqliteEventStore) current;
            }
            try {
                java.lang.reflect.Field field = current.getClass().getDeclaredField("delegate");
                field.setAccessible(true);
                current = field.get(current);
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }
}
