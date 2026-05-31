package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Builds drift signals for control summary (Stories 17.5 / 17.12). */
public final class DriftSignalService {

    private final RunManager runManager;
    private final DeploymentStore deploymentStore;
    private final DriftEngine engine;
    private final Clock clock;

    public DriftSignalService(RunManager runManager, DeploymentStore deploymentStore) {
        this(runManager, deploymentStore, new DriftEngine(), Clock.systemUTC());
    }

    DriftSignalService(
        RunManager runManager,
        DeploymentStore deploymentStore,
        DriftEngine engine,
        Clock clock
    ) {
        if (runManager == null) {
            throw new IllegalArgumentException("runManager is required");
        }
        if (deploymentStore == null) {
            throw new IllegalArgumentException("deploymentStore is required");
        }
        this.runManager = runManager;
        this.deploymentStore = deploymentStore;
        this.engine = engine != null ? engine : new DriftEngine();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public List<Map<String, Object>> buildDriftSignals() {
        Instant now = Instant.now(clock);
        Set<String> strategyIds = new LinkedHashSet<>();
        for (DeploymentRecord deployment : deploymentStore.listAll()) {
            strategyIds.add(deployment.strategyId());
        }
        for (RunRecord run : runManager.list(null)) {
            strategyIds.add(run.strategyId());
        }

        List<Map<String, Object>> signals = new ArrayList<>();
        for (String strategyId : strategyIds) {
            if (!StrategyCatalog.contains(strategyId)) {
                continue;
            }
            evaluateStrategy(strategyId, now).ifPresent(evaluation -> signals.add(evaluation.toMap()));
        }
        return List.copyOf(signals);
    }

    Optional<DriftEvaluation> evaluateStrategy(String strategyId, Instant now) {
        Optional<DeploymentRecord> deployment = deploymentStore.get(strategyId);
        List<RunRecord> strategyRuns = runManager.list(null).stream()
            .filter(run -> run.strategyId().equals(strategyId))
            .toList();

        if (deployment.isEmpty() && strategyRuns.isEmpty()) {
            return Optional.empty();
        }

        Optional<ExecutionLabel> deploymentLabel = deployment.map(DeploymentRecord::executionLabel);
        List<DriftEngine.BrokerObservation> brokerObservations = strategyRuns.stream()
            .map(run -> toBrokerObservation(run))
            .filter(obs -> obs.label().isBrokerBacked())
            .toList();

        if (deploymentLabel.isPresent() && !deploymentLabel.get().isBrokerBacked()) {
            return Optional.of(engine.evaluate(new DriftEngine.StrategyDriftInput(
                strategyId,
                deploymentLabel,
                Optional.empty(),
                Optional.empty(),
                brokerObservations,
                now)));
        }

        if (brokerObservations.isEmpty() && strategyRuns.stream()
            .allMatch(run -> !ControlSummaryService.executionLabel(run).isBrokerBacked())) {
            return Optional.of(engine.evaluate(new DriftEngine.StrategyDriftInput(
                strategyId,
                deploymentLabel,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                now)));
        }

        Optional<RunRecord> baselineRun = deployment
            .map(DeploymentRecord::sourceRunId)
            .flatMap(runManager::getRun);
        Optional<BacktestRunMetrics> baseline = baselineRun.map(BacktestRunMetrics::fromRun);
        Optional<String> baselineHash = baselineRun.map(RunRecord::configHash);

        return Optional.of(engine.evaluate(new DriftEngine.StrategyDriftInput(
            strategyId,
            deploymentLabel,
            baseline,
            baselineHash,
            brokerObservations,
            now)));
    }

    private DriftEngine.BrokerObservation toBrokerObservation(RunRecord run) {
        ExecutionLabel label = ControlSummaryService.executionLabel(run);
        BacktestRunMetrics metrics = BacktestRunMetrics.fromRun(run);
        int fillCount = (int) runManager.eventStore().replayAll(run.runId()).stream()
            .filter(event -> event.type() == RunEventType.FILL)
            .count();
        int tradeCount = Math.max(metrics.totalTrades(), fillCount);
        return new DriftEngine.BrokerObservation(
            run.runId(),
            label,
            run.startedAt(),
            run.configHash(),
            metrics,
            tradeCount);
    }
}
