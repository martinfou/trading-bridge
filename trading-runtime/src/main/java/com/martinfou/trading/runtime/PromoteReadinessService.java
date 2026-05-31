package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only promote readiness for operational runbook (Story 15.8 / PS-GR16). */
public final class PromoteReadinessService {

    public static final int SCHEMA_VERSION = 1;

    private final RunManager runManager;
    private final PromoteService promoteService;
    private final Clock clock;

    public PromoteReadinessService(RunManager runManager, PromoteService promoteService) {
        this(runManager, promoteService, Clock.systemUTC());
    }

    PromoteReadinessService(RunManager runManager, PromoteService promoteService, Clock clock) {
        if (runManager == null || promoteService == null) {
            throw new IllegalArgumentException("runManager and promoteService are required");
        }
        this.runManager = runManager;
        this.promoteService = promoteService;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public Map<String, Object> assess(String strategyId) {
        if (!StrategyCatalog.contains(strategyId)) {
            throw new IllegalArgumentException("Unknown strategy: " + strategyId);
        }

        Optional<DeploymentRecord> current = promoteService.deploymentStore().get(strategyId);
        RunMode targetMode = resolveTargetMode(current);
        List<GateCheckResult> gates = evaluateGates(strategyId, current, targetMode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", SCHEMA_VERSION);
        response.put("strategyId", strategyId);
        response.put("targetMode", targetMode.name());
        response.put("ready", gates.stream().allMatch(GateCheckResult::passed));
        response.put("gates", gates.stream().map(this::gateToMap).toList());
        current.ifPresent(d -> response.put("deployment", d.toMap()));
        current.map(DeploymentRecord::executionLabel).ifPresent(l -> response.put("executionLabel", l.name()));

        if (targetMode == RunMode.LIVE) {
            response.put("paperElapsedDays", paperElapsedDays(current));
            response.put("paperDaysRequired", promoteService.thresholds().paperDaysBeforeLive());
        }

        response.put("reconciliation", reconciliationStatus(strategyId));
        response.put("killSwitchActive", runManager.killSwitchRegistry().isKilled(strategyId));
        response.put("assessedAt", Instant.now(clock).toString());
        return Map.copyOf(response);
    }

    private RunMode resolveTargetMode(Optional<DeploymentRecord> current) {
        if (current.isEmpty()) {
            return RunMode.PAPER;
        }
        if (current.get().mode() == RunMode.PAPER) {
            return RunMode.LIVE;
        }
        return RunMode.LIVE;
    }

    private List<GateCheckResult> evaluateGates(
        String strategyId,
        Optional<DeploymentRecord> current,
        RunMode targetMode
    ) {
        PromoteGateThresholds thresholds = promoteService.thresholds();
        List<GateCheckResult> checks = new ArrayList<>();
        checks.add(PromoteGates.transitionAllowed(current.map(DeploymentRecord::mode), targetMode));

        if (targetMode == RunMode.PAPER) {
            Optional<RunRecord> runOpt = runManager.latestCompletedRun(strategyId, RunMode.BACKTEST);
            if (runOpt.isEmpty()) {
                checks.add(new GateCheckResult(
                    "backtest_exists", false, "No completed BACKTEST run for strategy " + strategyId));
            } else {
                RunRecord run = runOpt.get();
                BacktestRunMetrics metrics = BacktestRunMetrics.fromRun(run);
                checks.add(PromoteGates.backtestCompleted(run));
                checks.add(PromoteGates.goldenBaseline(run, thresholds));
                checks.add(PromoteGates.minTrades(metrics, thresholds));
                checks.add(PromoteGates.maxDrawdown(metrics, thresholds));
                checks.add(PromoteGates.minReturn(metrics, thresholds));
            }
        } else {
            checks.add(PromoteGates.requirePaperDeployment(current));
            checks.add(PromoteGates.paperExecutionLabel(current));
            checks.add(PromoteGates.paperDuration(current, thresholds, clock));
        }
        return checks;
    }

    private long paperElapsedDays(Optional<DeploymentRecord> current) {
        if (current.isEmpty() || !current.get().executionLabel().countsTowardPaperPeriod()) {
            return 0;
        }
        return ChronoUnit.DAYS.between(current.get().promotedAt(), Instant.now(clock));
    }

    private Map<String, Object> reconciliationStatus(String strategyId) {
        Set<String> affectedRunIds = new LinkedHashSet<>();
        int alertCount = 0;
        Instant lastAlertAt = null;

        for (RunRecord run : runManager.list(null)) {
            if (!run.strategyId().equals(strategyId)) {
                continue;
            }
            for (RunEvent event : runManager.eventStore().replayAll(run.runId())) {
                if (event.type() == RunEventType.RECONCILIATION_ALERT) {
                    alertCount++;
                    affectedRunIds.add(run.runId());
                    if (lastAlertAt == null || event.timestamp().isAfter(lastAlertAt)) {
                        lastAlertAt = event.timestamp();
                    }
                }
            }
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("alertCount", alertCount);
        status.put("affectedRunIds", List.copyOf(affectedRunIds));
        status.put("clear", alertCount == 0);
        if (lastAlertAt != null) {
            status.put("lastAlertAt", lastAlertAt.toString());
        }
        return status;
    }

    private Map<String, Object> gateToMap(GateCheckResult gate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", gate.name());
        map.put("passed", gate.passed());
        map.put("message", gate.message());
        if (gate.threshold() != null) {
            map.put("threshold", gate.threshold());
        }
        if (gate.actual() != null) {
            map.put("actual", gate.actual());
        }
        return map;
    }
}
