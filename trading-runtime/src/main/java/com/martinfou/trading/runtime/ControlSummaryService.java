package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.ForexPnL;
import com.martinfou.trading.core.Order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@code GET /control/summary} payload for prop-shop control room (Story 17.9).
 */
public final class ControlSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ControlSummaryService.class);

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

    public DriftSignalService driftSignalService() {
        return driftSignalService;
    }

    public Map<String, Object> buildSummary() {
        Instant now = Instant.now(clock);
        List<Map<String, Object>> runItems = new ArrayList<>();
        List<Map<String, Object>> gapSignals = new ArrayList<>();
        List<Map<String, Object>> staleSignals = new ArrayList<>();
        Optional<Instant> globalLastEvent = Optional.empty();
        int staleRunCount = 0;

        Map<String, List<RunRecord>> grouped = new LinkedHashMap<>();
        for (RunRecord record : runManager.list(null)) {
            String key = record.strategyId() + "|" + record.mode().name() + "|" + record.symbol();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }

        List<RunRecord> representativeRecords = new ArrayList<>();
        for (List<RunRecord> group : grouped.values()) {
            RunRecord representative = group.stream()
                .filter(r -> r.status() == RunRecord.Status.RUNNING || r.status() == RunRecord.Status.PAUSED)
                .findFirst()
                .orElseGet(() -> group.stream()
                    .max(Comparator.comparing(RunRecord::startedAt))
                    .orElse(group.get(0))
                );
            representativeRecords.add(representative);
        }

        for (RunRecord record : representativeRecords) {
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
            String displayStatus = record.status().name();
            Optional<AutoCloseable> execOpt = runManager.getActiveExecutor(record.runId());
            if (execOpt.isPresent() && execOpt.get() instanceof OandaStreamingExecutor exec) {
                if (exec.isSuspendedDaily()) {
                    displayStatus = "SUSPENDED_DAILY";
                } else if (exec.isSuspendedWeekly()) {
                    displayStatus = "SUSPENDED_WEEKLY";
                } else if (exec.getCooldownUntil() != null && now.isBefore(exec.getCooldownUntil())) {
                    displayStatus = "COOLDOWN";
                    item.put("cooldownUntil", exec.getCooldownUntil().toString());
                    long secondsLeft = java.time.Duration.between(now, exec.getCooldownUntil()).getSeconds();
                    item.put("cooldownSecondsRemaining", Math.max(0, secondsLeft));
                }
                if (exec.getLastBid() > 0) {
                    item.put("lastBid", exec.getLastBid());
                }
                if (exec.getLastAsk() > 0) {
                    item.put("lastAsk", exec.getLastAsk());
                }
            }
            item.put("status", displayStatus);
            item.put("isStale", isStale);

            String brokerAccountId = record.configSnapshot().containsKey("brokerAccountId")
                ? String.valueOf(record.configSnapshot().get("brokerAccountId"))
                : null;
            if (label.isBrokerBacked() && brokerAccountId != null && runManager != null) {
                var registry = runManager.brokerAccountRegistry();
                if (registry != null) {
                    var creds = registry.credentials(brokerAccountId);
                    if (creds.isPresent()) {
                        item.put("resolvedAccountId", creds.get().accountId());
                        item.put("maskedAccountId", BrokerAccountRegistry.maskAccountId(creds.get().accountId()));
                    } else if (label.isIbkrBroker()) {
                        var ibkr = registry.ibkrConnection(brokerAccountId);
                        ibkr.ifPresent(cfg -> {
                            item.put("resolvedAccountId", cfg.accountId());
                            item.put("maskedAccountId", BrokerAccountRegistry.maskAccountId(cfg.accountId()));
                        });
                    }
                }
            }

            try {
                Optional<Instant> lastTradeAt = runManager.eventStore().replayAll(record.runId()).stream()
                    .filter(e -> e.type() == com.martinfou.trading.backtest.events.RunEventType.FILL
                        || e.type() == com.martinfou.trading.backtest.events.RunEventType.REJECT)
                    .map(com.martinfou.trading.backtest.events.RunEvent::timestamp)
                    .max(Comparator.naturalOrder());
                lastTradeAt.ifPresent(t -> item.put("lastTradeAt", t.toString()));
            } catch (Exception e) {
                log.warn("Failed to compute lastTradeAt for run {}: {}", record.runId(), e.getMessage());
            }

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

            StrategyPnLMetrics pnlMetrics = calculatePnLMetrics(record);
            item.put("openTrades", pnlMetrics.openTradesCount);
            item.put("openPnL", pnlMetrics.openPnL);
            item.put("realizedPnL", pnlMetrics.realizedPnL);
            item.put("totalPnL", pnlMetrics.totalPnL);
            item.put("netQuantity", pnlMetrics.netQuantity);
            item.put("netSide", pnlMetrics.netSide);
            item.put("positions", getPositions(record, label));

            if (driftSignalService != null) {
                driftSignalService.evaluateStrategy(record.strategyId(), now).ifPresent(evaluation -> {
                    item.put("driftComparison", evaluation.toMap());
                });
            }

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
        return latestStored.map(stored -> stored.event().timestamp())
            .filter(t -> !t.isBefore(record.startedAt()));
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

    private static class StrategyPnLMetrics {
        int openTradesCount = 0;
        double openPnL = 0.0;
        double realizedPnL = 0.0;
        double totalPnL = 0.0;
        double netQuantity = 0.0;
        String netSide = "FLAT";
    }

    private List<Map<String, Object>> getPositions(RunRecord record, ExecutionLabel label) {
        List<Map<String, Object>> positions = new ArrayList<>();
        boolean querySucceeded = false;
        if (record.status() == RunRecord.Status.RUNNING && label.isBrokerBacked()) {
            try {
                String accountId = record.configSnapshot().containsKey("brokerAccountId")
                    ? String.valueOf(record.configSnapshot().get("brokerAccountId"))
                    : null;
                if (runManager != null && runManager.brokerAccountRegistry() != null) {
                    var brokerOpt = runManager.brokerAccountRegistry().broker(accountId, label);
                    if (brokerOpt.isPresent()) {
                        var broker = brokerOpt.get();
                        broker.connect();
                        List<com.martinfou.trading.core.Position> brokerPosList = broker.getPositions();
                        querySucceeded = true;
                        java.util.Set<String> runOrderIds = new java.util.HashSet<>();
                        if (runManager.eventStore() != null) {
                            for (var e : runManager.eventStore().replayAll(record.runId())) {
                                if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
                                    runOrderIds.add(String.valueOf(e.payload().get("orderId")));
                                }
                            }
                        }
                        var journalPositions = JournalPositions.fromFills(runManager.eventStore().replayAll(record.runId()));
                        for (var pos : brokerPosList) {
                            if (pos.symbol().equalsIgnoreCase(record.symbol()) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(record.symbol().replace("/", "_").replace("-", "_"))) {
                                java.time.Instant resolvedEntryTime = pos.entryTime();
                                if (resolvedEntryTime == null || resolvedEntryTime.equals(java.time.Instant.EPOCH)) {
                                    String journalKey = pos.symbol() + ":" + pos.side().name();
                                    var jp = journalPositions.get(journalKey);
                                    if (jp != null && jp.entryTime() != null) {
                                        resolvedEntryTime = jp.entryTime();
                                    }
                                }
                                boolean match = false;
                                if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
                                    if (runOrderIds.contains(pos.clientTag())) {
                                        match = true;
                                    }
                                } else {
                                    int activeRuns = 0;
                                    for (RunRecord r : runManager.list(null)) {
                                        if (r.status() == RunRecord.Status.RUNNING) {
                                            String rs = r.symbol();
                                            if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
                                                activeRuns++;
                                            }
                                        }
                                    }
                                    if (activeRuns == 1) {
                                        match = true;
                                    }
                                }
                                if (match) {
                                    positions.add(Map.of(
                                        "symbol", pos.symbol(),
                                        "side", pos.side().name(),
                                        "quantity", pos.quantity(),
                                        "entryTime", resolvedEntryTime != null ? resolvedEntryTime.toString() : "",
                                        "entryPrice", pos.entryPrice(),
                                        "stopLoss", pos.stopLoss(),
                                        "takeProfit", pos.takeProfit()
                                    ));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // fallback to journal fills
            }
        }
        if (!querySucceeded) {
            List<Map<String, Object>> journalPositions = JournalPositions.fromFills(runManager.eventStore().replayAll(record.runId())).values().stream()
                .map(pos -> Map.<String, Object>of(
                    "symbol", pos.symbol(),
                    "side", pos.side().name(),
                    "quantity", pos.quantity(),
                    "entryTime", pos.entryTime() != null ? pos.entryTime().toString() : ""
                ))
                .toList();
            positions.addAll(journalPositions);
        }
        return positions;
    }

    private StrategyPnLMetrics calculatePnLMetrics(RunRecord record) {
        StrategyPnLMetrics metrics = new StrategyPnLMetrics();
        
        String strategyId = record.strategyId();
        RunMode mode = record.mode();

        List<RunRecord> siblingRuns = runManager.runRecordStore().listAll().stream()
            .filter(r -> java.util.Objects.equals(strategyId, r.strategyId())
                && mode == r.mode()
                && java.util.Objects.equals(record.symbol(), r.symbol()))
            .toList();

        double cumulativeRealizedPnL = 0.0;
        double openPnL = 0.0;
        int openTradesCount = 0;
        double netBuyQty = 0.0;
        double netSellQty = 0.0;

        // Get latest price if running live/paper
        double currentPrice = 0.0;
        Optional<AutoCloseable> execOpt = runManager.getActiveExecutor(record.runId());
        if (execOpt.isPresent() && execOpt.get() instanceof OandaStreamingExecutor exec) {
            currentPrice = exec.getLastMidPrice();
        }

        for (RunRecord sibling : siblingRuns) {
            if (sibling.status() == RunRecord.Status.RUNNING) {
                List<RunEvent> siblingEvents = runManager.eventStore().replayAll(sibling.runId());
                com.martinfou.trading.backtest.persistence.TradeReconstructor.ReconstructionResult siblingResult =
                    com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstructWithOpen(siblingEvents);

                cumulativeRealizedPnL += siblingResult.closedTrades().stream().mapToDouble(com.martinfou.trading.core.Trade::pnl).sum();

                double sibPrice = sibling.runId().equals(record.runId()) ? currentPrice : 0.0;
                if (sibPrice == 0.0) {
                    Optional<AutoCloseable> sibExecOpt = runManager.getActiveExecutor(sibling.runId());
                    if (sibExecOpt.isPresent() && sibExecOpt.get() instanceof OandaStreamingExecutor sibExec) {
                        sibPrice = sibExec.getLastMidPrice();
                    }
                }

                for (com.martinfou.trading.core.Trade t : siblingResult.openTrades()) {
                    openTradesCount++;
                    if (t.side() == Order.Side.BUY) {
                        netBuyQty += t.quantity();
                    } else if (t.side() == Order.Side.SELL) {
                        netSellQty += t.quantity();
                    }
                    if (sibPrice > 0.0) {
                        openPnL += ForexPnL.pnlUsd(
                            t.symbol(),
                            t.side(),
                            t.entryPrice(),
                            sibPrice,
                            t.quantity()
                        );
                    }
                }
            } else {
                List<com.martinfou.trading.core.Trade> closedTrades = runManager.tradeStore().getTrades(sibling.runId());
                if (closedTrades.isEmpty()) {
                    List<RunEvent> siblingEvents = runManager.eventStore().replayAll(sibling.runId());
                    closedTrades = com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstruct(siblingEvents);
                }
                cumulativeRealizedPnL += closedTrades.stream().mapToDouble(com.martinfou.trading.core.Trade::pnl).sum();
            }
        }

        metrics.openTradesCount = openTradesCount;
        metrics.openPnL = openPnL;
        metrics.realizedPnL = cumulativeRealizedPnL;
        metrics.totalPnL = cumulativeRealizedPnL + openPnL;

        double diff = netBuyQty - netSellQty;
        if (diff > 0.0) {
            metrics.netQuantity = diff;
            metrics.netSide = "LONG";
        } else if (diff < 0.0) {
            metrics.netQuantity = -diff;
            metrics.netSide = "SHORT";
        } else {
            metrics.netQuantity = 0.0;
            metrics.netSide = "FLAT";
        }

        return metrics;
    }
}
