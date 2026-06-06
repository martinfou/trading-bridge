package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Evaluates promote gates and updates {@link DeploymentStore} on success.
 */
public final class PromoteService {

    public record PromoteRequest(String targetMode, String runId, String executionLabel, String brokerAccountId) {

        public PromoteRequest(String targetMode, String runId) {
            this(targetMode, runId, null, null);
        }

        public PromoteRequest(String targetMode, String runId, String executionLabel) {
            this(targetMode, runId, executionLabel, null);
        }
    }

    public record PromoteResponse(
        String strategyId,
        RunMode targetMode,
        boolean promoted,
        List<GateCheckResult> checks,
        DeploymentRecord deployment
    ) {
        static PromoteResponse rejected(String strategyId, RunMode targetMode, List<GateCheckResult> checks) {
            return new PromoteResponse(strategyId, targetMode, false, checks, null);
        }

        static PromoteResponse accepted(DeploymentRecord deployment) {
            return new PromoteResponse(
                deployment.strategyId(),
                deployment.mode(),
                true,
                deployment.checks(),
                deployment);
        }
    }

    private final RunManager runManager;
    private final DeploymentStore deploymentStore;
    private PromoteGateThresholds thresholds;
    private final Clock clock;
    private final List<ValidationModule> validationModules;
    private final BrokerAccountRegistry brokerAccountRegistry;
    private final java.util.function.Function<String, Boolean> credentialsConfigured;

    public PromoteService(RunManager runManager, DeploymentStore deploymentStore) {
        this(runManager, deploymentStore, PromoteGateThresholds.loadDefault(), Clock.systemUTC(), List.of(), null, null);
    }

    PromoteService(
        RunManager runManager,
        DeploymentStore deploymentStore,
        PromoteGateThresholds thresholds,
        Clock clock,
        List<ValidationModule> validationModules
    ) {
        this(runManager, deploymentStore, thresholds, clock, validationModules, null, null);
    }

    PromoteService(
        RunManager runManager,
        DeploymentStore deploymentStore,
        PromoteGateThresholds thresholds,
        Clock clock,
        List<ValidationModule> validationModules,
        BooleanSupplier oandaCredentialsPresent
    ) {
        this(runManager, deploymentStore, thresholds, clock, validationModules, null,
            accountId -> oandaCredentialsPresent != null && oandaCredentialsPresent.getAsBoolean());
    }

    PromoteService(
        RunManager runManager,
        DeploymentStore deploymentStore,
        PromoteGateThresholds thresholds,
        Clock clock,
        List<ValidationModule> validationModules,
        BrokerAccountRegistry brokerAccountRegistry,
        java.util.function.Function<String, Boolean> credentialsConfigured
    ) {
        if (runManager == null || deploymentStore == null) {
            throw new IllegalArgumentException("runManager and deploymentStore are required");
        }
        this.runManager = runManager;
        this.deploymentStore = deploymentStore;
        this.thresholds = thresholds != null ? thresholds : PromoteGateThresholds.DEFAULT;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.validationModules = validationModules != null ? List.copyOf(validationModules) : List.of();
        this.brokerAccountRegistry = brokerAccountRegistry != null
            ? brokerAccountRegistry
            : BrokerAccountRegistry.loadDefault();
        this.credentialsConfigured = credentialsConfigured != null
            ? credentialsConfigured
            : this.brokerAccountRegistry::credentialsConfigured;
    }

    public PromoteResponse promote(String strategyId, PromoteRequest request) {
        if (!StrategyCatalog.contains(strategyId)) {
            throw new IllegalArgumentException("Unknown strategy: " + strategyId);
        }
        RunMode targetMode = RunMode.valueOf(request.targetMode().toUpperCase());
        if (targetMode != RunMode.PAPER && targetMode != RunMode.LIVE) {
            throw new IllegalArgumentException("targetMode must be PAPER or LIVE");
        }

        List<GateCheckResult> checks = new ArrayList<>();
        Optional<DeploymentRecord> current = deploymentStore.get(strategyId);

        checks.add(PromoteGates.transitionAllowed(current.map(DeploymentRecord::mode), targetMode));

        if (targetMode == RunMode.PAPER) {
            ExecutionLabel paperLabel = resolvePaperExecutionLabel(request.executionLabel());
            String accountId = resolvePromoteBrokerAccountId(request.brokerAccountId(), current);
            checks.add(PromoteGates.brokerAccountKnown(accountId, brokerAccountRegistry.contains(accountId)));
            checks.add(PromoteGates.oandaCredentialsForPaper(
                paperLabel, credentialsConfigured.apply(accountId)));
            checks.add(PromoteGates.ibkrCredentialsForPaper(
                paperLabel, accountId, brokerAccountRegistry));

            Optional<RunRecord> runOpt = findBacktestRun(strategyId, request.runId());
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
                ValidationAuditBuffer auditBuffer = new ValidationAuditBuffer(false);
                ValidationContext validationContext = new ValidationContext(
                    strategyId, run, runManager.eventStore(), auditBuffer);
                checks.add(PromoteGates.validationModule(
                    validationContext,
                    thresholds,
                    validationModules));
                if (checks.stream().allMatch(GateCheckResult::passed)) {
                    auditBuffer.flush(run, runManager.eventStore());
                }
            }
        } else {
            checks.add(PromoteGates.requirePaperDeployment(current));
            checks.add(PromoteGates.paperExecutionLabel(current));
            checks.add(PromoteGates.paperDuration(current, thresholds, clock));
            String accountId = current.map(DeploymentRecord::brokerAccountId).orElse(null);
            if (request.brokerAccountId() != null && !request.brokerAccountId().isBlank()) {
                BrokerAccountRegistry.assertRoutingAllowed(request.brokerAccountId(), accountId);
            }
        }

        if (checks.stream().anyMatch(c -> !c.passed())) {
            return PromoteResponse.rejected(strategyId, targetMode, checks);
        }

        String sourceRunId = request.runId();
        if (targetMode == RunMode.PAPER && (sourceRunId == null || sourceRunId.isBlank())) {
            sourceRunId = runManager.latestCompletedRun(strategyId, RunMode.BACKTEST)
                .map(RunRecord::runId)
                .orElse(null);
        }

        String brokerAccountId = targetMode == RunMode.PAPER
            ? resolvePromoteBrokerAccountId(request.brokerAccountId(), current)
            : current.map(DeploymentRecord::brokerAccountId).orElse(null);

        DeploymentRecord deployment = new DeploymentRecord(
            strategyId,
            targetMode,
            resolvePromotedAt(current, targetMode, resolveExecutionLabel(targetMode, request.executionLabel())),
            sourceRunId,
            List.copyOf(checks),
            resolveExecutionLabel(targetMode, request.executionLabel()),
            brokerAccountId);
        deploymentStore.save(deployment);
        return PromoteResponse.accepted(deployment);
    }

    private ExecutionLabel resolvePaperExecutionLabel(String requestedLabel) {
        if (requestedLabel != null && !requestedLabel.isBlank()) {
            ExecutionLabel label = ExecutionLabel.parse(requestedLabel);
            if (label != ExecutionLabel.PAPER_STUB
                && label != ExecutionLabel.PAPER_OANDA
                && label != ExecutionLabel.PAPER_IBKR) {
                throw new IllegalArgumentException(
                    "PAPER promote executionLabel must be PAPER_STUB, PAPER_OANDA, or PAPER_IBKR");
            }
            return label;
        }
        return credentialsConfigured.apply(BrokerAccountRegistry.DEFAULT_ID)
            ? ExecutionLabel.PAPER_OANDA
            : ExecutionLabel.PAPER_STUB;
    }

    private String resolvePromoteBrokerAccountId(
        String requestedAccountId,
        Optional<DeploymentRecord> current
    ) {
        String deploymentAccountId = current.map(DeploymentRecord::brokerAccountId).orElse(null);
        BrokerAccountRegistry.assertRoutingAllowed(requestedAccountId, deploymentAccountId);
        if (requestedAccountId != null && !requestedAccountId.isBlank()) {
            return requestedAccountId.trim();
        }
        if (deploymentAccountId != null && !deploymentAccountId.isBlank()) {
            return deploymentAccountId;
        }
        return BrokerAccountRegistry.DEFAULT_ID;
    }

    private ExecutionLabel resolveExecutionLabel(RunMode targetMode, String requestedLabel) {
        if (targetMode == RunMode.PAPER) {
            return resolvePaperExecutionLabel(requestedLabel);
        }
        return ExecutionLabel.forPromotedMode(targetMode);
    }

    /**
     * Preserves {@link DeploymentRecord#promotedAt} when re-promoting PAPER while already on
     * {@link ExecutionLabel#PAPER_OANDA} (PS-GR4 deployment lineage).
     */
    static Instant resolvePromotedAt(
        Optional<DeploymentRecord> current,
        RunMode targetMode,
        ExecutionLabel newLabel,
        Instant now
    ) {
        if (targetMode != RunMode.PAPER || current.isEmpty()) {
            return now;
        }
        DeploymentRecord previous = current.get();
        if (previous.mode() != RunMode.PAPER) {
            return now;
        }
        if (newLabel == ExecutionLabel.PAPER_OANDA
            && previous.executionLabel() == ExecutionLabel.PAPER_OANDA) {
            return previous.promotedAt();
        }
        return now;
    }

    private Instant resolvePromotedAt(
        Optional<DeploymentRecord> current,
        RunMode targetMode,
        ExecutionLabel newLabel
    ) {
        return resolvePromotedAt(current, targetMode, newLabel, Instant.now(clock));
    }

    public PromoteGateThresholds thresholds() {
        return thresholds;
    }

    public synchronized void setThresholds(PromoteGateThresholds thresholds) {
        if (thresholds == null) {
            throw new IllegalArgumentException("thresholds must not be null");
        }
        this.thresholds = thresholds;
    }

    public DeploymentStore deploymentStore() {
        return deploymentStore;
    }

    private Optional<RunRecord> findBacktestRun(String strategyId, String runId) {
        if (runId != null && !runId.isBlank()) {
            return runManager.getRun(runId).flatMap(run -> {
                if (!run.strategyId().equals(strategyId)) {
                    throw new IllegalArgumentException("Run does not belong to strategy " + strategyId);
                }
                if (run.mode() != RunMode.BACKTEST) {
                    throw new IllegalArgumentException("Promote to PAPER requires a BACKTEST run");
                }
                if (run.status() != RunRecord.Status.COMPLETED) {
                    return Optional.empty();
                }
                return Optional.of(run);
            });
        }
        return runManager.latestCompletedRun(strategyId, RunMode.BACKTEST);
    }
}
