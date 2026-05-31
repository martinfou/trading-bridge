package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Emergency kill switch for broker-backed deployments (Story 16.6).
 * Sets a strategy-level flag and journals {@code OPERATOR_ACTION} on affected runs.
 */
public final class KillSwitchService {

    public static final String ACTION_KILL = "KILL";

    public record KillRequest(String actor, String reason) {}

    public record KillResponse(
        String strategyId,
        boolean killed,
        List<String> affectedRunIds,
        Instant timestamp
    ) {}

    private final RunManager runManager;
    private final DeploymentStore deploymentStore;
    private final KillSwitchRegistry registry;
    private final Clock clock;

    public KillSwitchService(RunManager runManager, DeploymentStore deploymentStore, KillSwitchRegistry registry) {
        this(runManager, deploymentStore, registry, Clock.systemUTC());
    }

    KillSwitchService(
        RunManager runManager,
        DeploymentStore deploymentStore,
        KillSwitchRegistry registry,
        Clock clock
    ) {
        if (runManager == null || deploymentStore == null || registry == null) {
            throw new IllegalArgumentException("runManager, deploymentStore, and registry are required");
        }
        this.runManager = runManager;
        this.deploymentStore = deploymentStore;
        this.registry = registry;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public KillSwitchRegistry registry() {
        return registry;
    }

    public KillResponse kill(String strategyId, KillRequest request) {
        if (!StrategyCatalog.contains(strategyId)) {
            throw new IllegalArgumentException("Unknown strategy: " + strategyId);
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        String actor = requireNonBlank(request.actor(), "actor");
        String reason = requireNonBlank(request.reason(), "reason");

        DeploymentRecord deployment = deploymentStore.get(strategyId)
            .orElseThrow(() -> new IllegalArgumentException("Strategy not deployed: " + strategyId));
        if (!isKillEligible(deployment.executionLabel())) {
            throw new IllegalArgumentException(
                "Kill switch applies to PAPER_OANDA or LIVE deployments only (actual: "
                    + deployment.executionLabel().name() + ")");
        }

        registry.kill(strategyId);
        Instant timestamp = Instant.now(clock);

        List<String> affectedRunIds = new ArrayList<>();
        for (RunRecord run : findRunningBrokerRuns(strategyId)) {
            appendOperatorAction(run, actor, reason, timestamp);
            affectedRunIds.add(run.runId());
        }

        return new KillResponse(strategyId, true, List.copyOf(affectedRunIds), timestamp);
    }

    private void appendOperatorAction(RunRecord run, String actor, String reason, Instant timestamp) {
        RunEvent event = RunEvent.operatorAction(
            run.runId(),
            run.strategyId(),
            run.symbol(),
            run.mode(),
            ACTION_KILL,
            actor,
            reason,
            timestamp);
        runManager.eventStore().append(run.runId(), event);
        run.noteEventAt(timestamp);
    }

    private List<RunRecord> findRunningBrokerRuns(String strategyId) {
        return runManager.list(null).stream()
            .filter(r -> r.strategyId().equals(strategyId))
            .filter(r -> r.status() == RunRecord.Status.RUNNING)
            .filter(r -> isKillEligible(executionLabelFrom(r)))
            .toList();
    }

    static boolean isKillEligible(ExecutionLabel label) {
        return label.isBrokerBacked();
    }

    static ExecutionLabel executionLabelFrom(RunRecord run) {
        Object resolved = run.configSnapshot().get("resolvedExecutionLabel");
        if (resolved != null && !resolved.toString().isBlank()) {
            return ExecutionLabel.parse(resolved.toString());
        }
        return ExecutionLabel.forRunMode(run.mode());
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
