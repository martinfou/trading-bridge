package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.backtest.persistence.BacktestRunDetails;
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
                List.of(),
                brokerObservations,
                now)));
        }

        if (brokerObservations.isEmpty() && strategyRuns.stream()
            .allMatch(run -> !ControlSummaryService.executionLabel(run).isBrokerBacked())) {
            return Optional.of(engine.evaluate(new DriftEngine.StrategyDriftInput(
                strategyId,
                deploymentLabel,
                Optional.empty(),
                List.of(),
                List.of(),
                now)));
        }

        Optional<BacktestRunDetails> baseline = Optional.empty();
        List<com.martinfou.trading.core.Trade> baselineTrades = List.of();

        Optional<RunRecord> baselineRun = deployment
            .map(DeploymentRecord::sourceRunId)
            .flatMap(runManager::getRun);

        if (baselineRun.isPresent()) {
            String sourceId = baselineRun.get().runId();
            java.nio.file.Path dbPath = com.martinfou.trading.backtest.persistence.BacktestPersistenceService.resolveDefaultDbPath();
            try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
                var detailsOpt = store.get(sourceId);
                if (detailsOpt.isPresent()) {
                    baseline = detailsOpt;
                    baselineTrades = store.tradeStore().getTrades(sourceId);
                }
            } catch (Exception ignored) {}
        } else if (deployment.isPresent() && deployment.get().sourceRunId() != null) {
            String sourceId = deployment.get().sourceRunId();
            java.nio.file.Path dbPath = com.martinfou.trading.backtest.persistence.BacktestPersistenceService.resolveDefaultDbPath();
            try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
                var detailsOpt = store.get(sourceId);
                if (detailsOpt.isPresent()) {
                    baseline = detailsOpt;
                    baselineTrades = store.tradeStore().getTrades(sourceId);
                }
            } catch (Exception ignored) {}
        }

        return Optional.of(engine.evaluate(new DriftEngine.StrategyDriftInput(
            strategyId,
            deploymentLabel,
            baseline,
            baselineTrades,
            brokerObservations,
            now)));
    }

    private DriftEngine.BrokerObservation toBrokerObservation(RunRecord run) {
        ExecutionLabel label = ControlSummaryService.executionLabel(run);
        List<com.martinfou.trading.backtest.events.RunEvent> events = runManager.eventStore().replayAll(run.runId());
        List<com.martinfou.trading.core.Trade> actualTrades = com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstruct(events);
        RunConfigSnapshot config = RunConfigSnapshot.fromRecord(run);

        return new DriftEngine.BrokerObservation(
            run.runId(),
            label,
            run.startedAt(),
            run.configHash(),
            actualTrades,
            config);
    }
}
