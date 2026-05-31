package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds {@code GET /control/summary} payload for prop-shop control room (Story 17.9).
 */
public final class ControlSummaryService {

    public static final int SCHEMA_VERSION = 1;

    private final RunManager runManager;
    private final long staleThresholdSeconds;
    private final Clock clock;
    private final DriftSignalService driftSignalService;

    public ControlSummaryService(RunManager runManager) {
        this(runManager, StaleThresholds.loadDefault().runningStaleThresholdSeconds(), Clock.systemUTC(), null);
    }

    public ControlSummaryService(RunManager runManager, DeploymentStore deploymentStore) {
        this(
            runManager,
            StaleThresholds.loadDefault().runningStaleThresholdSeconds(),
            Clock.systemUTC(),
            deploymentStore != null ? new DriftSignalService(runManager, deploymentStore) : null);
    }

    ControlSummaryService(RunManager runManager, long staleThresholdSeconds, Clock clock) {
        this(runManager, staleThresholdSeconds, clock, null);
    }

    ControlSummaryService(
        RunManager runManager,
        long staleThresholdSeconds,
        Clock clock,
        DriftSignalService driftSignalService
    ) {
        if (runManager == null) {
            throw new IllegalArgumentException("runManager is required");
        }
        this.runManager = runManager;
        this.staleThresholdSeconds = staleThresholdSeconds;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.driftSignalService = driftSignalService;
    }

    public Map<String, Object> buildSummary() {
        Instant now = Instant.now(clock);
        List<Map<String, Object>> runItems = new ArrayList<>();
        List<Map<String, Object>> gapSignals = new ArrayList<>();
        List<Map<String, Object>> staleSignals = new ArrayList<>();
        Optional<Instant> globalLastEvent = Optional.empty();
        int staleRunCount = 0;

        for (RunRecord record : runManager.list(null)) {
            EventGapDetector.Result gaps = EventGapDetector.analyze(record.runId(), runManager.eventStore());
            Optional<StoredRunEvent> latestStored = latestStoredEvent(record.runId());
            Optional<Instant> lastEventAt = resolveLastEventAt(record, latestStored);

            if (lastEventAt.isPresent()) {
                globalLastEvent = globalLastEvent
                    .map(existing -> existing.isAfter(lastEventAt.get()) ? existing : lastEventAt.get())
                    .or(() -> lastEventAt);
            }

            boolean isStale = isStale(record, lastEventAt, now);
            ExecutionLabel label = executionLabel(record);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("runId", record.runId());
            item.put("strategyId", record.strategyId());
            item.put("symbol", record.symbol());
            item.put("mode", record.mode().name());
            item.put("executionLabel", label.name());
            item.put("executionLabelMeta", ExecutionLabelCatalog.of(label).toMap());
            item.put("status", record.status().name());
            item.put("isStale", isStale);
            lastEventAt.ifPresent(t -> item.put("lastEventAt", t.toString()));
            item.put("configSnapshot", record.configSnapshot());
            item.put("configHash", record.configHash());
            item.put("eventCount", runManager.eventStore().count(record.runId()));
            item.put("gaps", gaps.gaps().stream()
                .map(g -> Map.of("fromSequence", g.fromSequence(), "toSequence", g.toSequence()))
                .toList());
            latestStored.ifPresent(stored -> item.put("latestEvent", toEventMap(stored)));

            runManager.dailyDrawdownMetrics(record.runId()).ifPresent(metrics -> {
                item.put("dailyDrawdownPct", metrics.drawdownPct());
                item.put("maxDailyDrawdownPct", metrics.maxDailyDrawdownPct());
                item.put("dailyDdBreached", metrics.breached());
            });

            runItems.add(item);

            if (isStale) {
                staleRunCount++;
                Map<String, Object> staleSignal = new LinkedHashMap<>();
                staleSignal.put("runId", record.runId());
                staleSignal.put("strategyId", record.strategyId());
                staleSignal.put("executionLabel", label.name());
                staleSignal.put("status", record.status().name());
                lastEventAt.ifPresent(t -> staleSignal.put("lastEventAt", t.toString()));
                if (lastEventAt.isPresent()) {
                    staleSignal.put(
                        "secondsSinceLastEvent",
                        Math.max(0, Duration.between(lastEventAt.get(), now).getSeconds()));
                } else {
                    staleSignal.put(
                        "secondsSinceLastEvent",
                        Math.max(0, Duration.between(record.startedAt(), now).getSeconds()));
                }
                staleSignals.add(staleSignal);
            }

            if (!gaps.gaps().isEmpty()) {
                Map<String, Object> signal = new LinkedHashMap<>();
                signal.put("runId", record.runId());
                signal.put("strategyId", record.strategyId());
                signal.put("executionLabel", label.name());
                signal.put("gaps", item.get("gaps"));
                gapSignals.add(signal);
            }
        }

        runItems.sort(runSeverityComparator());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", SCHEMA_VERSION);
        summary.put("executionLabelCatalog", ExecutionLabelCatalog.catalogMap());
        summary.put("freshness", buildFreshness(globalLastEvent, now, staleRunCount));
        summary.put("runs", runItems);
        List<Map<String, Object>> driftSignals = driftSignalService != null
            ? driftSignalService.buildDriftSignals()
            : List.of();
        summary.put("signals", Map.of(
            "gaps", gapSignals,
            "drift", driftSignals,
            "stale", staleSignals));
        return Map.copyOf(summary);
    }

    private Map<String, Object> buildFreshness(Optional<Instant> globalLastEvent, Instant now, int staleRunCount) {
        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("staleThresholdSeconds", staleThresholdSeconds);
        freshness.put("staleRunCount", staleRunCount);
        if (globalLastEvent.isEmpty()) {
            freshness.put("lastEventAt", null);
            freshness.put("secondsSinceLastEvent", null);
            return freshness;
        }
        Instant last = globalLastEvent.get();
        long seconds = Duration.between(last, now).getSeconds();
        freshness.put("lastEventAt", last.toString());
        freshness.put("secondsSinceLastEvent", Math.max(0, seconds));
        return freshness;
    }

    private boolean isStale(RunRecord record, Optional<Instant> lastEventAt, Instant now) {
        if (record.status() != RunRecord.Status.RUNNING) {
            return false;
        }
        if (lastEventAt.isEmpty()) {
            return Duration.between(record.startedAt(), now).getSeconds() > staleThresholdSeconds;
        }
        return Duration.between(lastEventAt.get(), now).getSeconds() > staleThresholdSeconds;
    }

    private Optional<Instant> resolveLastEventAt(RunRecord record, Optional<StoredRunEvent> latestStored) {
        if (record.lastEventAt().isPresent()) {
            return record.lastEventAt();
        }
        return latestStored.map(stored -> stored.event().timestamp());
    }

    private Optional<StoredRunEvent> latestStoredEvent(String runId) {
        EventGapDetector.Result gaps = EventGapDetector.analyze(runId, runManager.eventStore());
        if (gaps.maxSequence() <= 0) {
            return Optional.empty();
        }
        List<StoredRunEvent> tail = runManager.eventStore()
            .queryWithSequence(runId, gaps.maxSequence() - 1, 1);
        return tail.isEmpty() ? Optional.empty() : Optional.of(tail.getFirst());
    }

    static ExecutionLabel executionLabel(RunRecord record) {
        Object resolved = record.configSnapshot().get("resolvedExecutionLabel");
        if (resolved != null && !resolved.toString().isBlank()) {
            return ExecutionLabel.parse(resolved.toString());
        }
        return ExecutionLabel.forRunMode(record.mode());
    }

    private static Map<String, Object> toEventMap(StoredRunEvent stored) {
        RunEvent event = stored.event();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sequence", stored.sequence());
        map.put("type", event.type().name());
        map.put("timestamp", event.timestamp().toString());
        map.put("payload", event.payload());
        return map;
    }

    private static Comparator<Map<String, Object>> runSeverityComparator() {
        return Comparator
            .comparing((Map<String, Object> r) -> !(Boolean) r.get("isStale"))
            .thenComparing(r -> ((List<?>) r.get("gaps")).isEmpty())
            .thenComparing(r -> (String) r.getOrDefault("lastEventAt", ""), Comparator.reverseOrder());
    }
}
