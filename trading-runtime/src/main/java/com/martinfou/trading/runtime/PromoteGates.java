package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.GoldenBacktestBaseline;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure promote gate evaluations with documented numeric thresholds. */
final class PromoteGates {

    private PromoteGates() {}

    static GateCheckResult transitionAllowed(Optional<RunMode> current, RunMode target) {
        if (target == RunMode.PAPER) {
            if (current.isEmpty() || current.get() == RunMode.PAPER) {
                return new GateCheckResult("transition", true, "Allowed: → PAPER");
            }
            return new GateCheckResult("transition", false, "Already deployed beyond PAPER");
        }
        if (current.isPresent() && current.get() == RunMode.PAPER) {
            return new GateCheckResult("transition", true, "Allowed: PAPER → LIVE");
        }
        return new GateCheckResult("transition", false, "Must deploy to PAPER before LIVE");
    }

    static GateCheckResult backtestCompleted(RunRecord run) {
        boolean passed = run.status() == RunRecord.Status.COMPLETED;
        return new GateCheckResult(
            "backtest_completed",
            passed,
            passed ? "Backtest run completed" : "Backtest run not completed");
    }

    static GateCheckResult minTrades(BacktestRunMetrics metrics, PromoteGateThresholds thresholds) {
        boolean passed = metrics.totalTrades() >= thresholds.minTrades();
        return GateCheckResult.numeric(
            "min_trades",
            passed,
            passed
                ? "Trades " + metrics.totalTrades() + " >= " + thresholds.minTrades()
                : "Trades " + metrics.totalTrades() + " < " + thresholds.minTrades(),
            thresholds.minTrades(),
            metrics.totalTrades());
    }

    static GateCheckResult maxDrawdown(BacktestRunMetrics metrics, PromoteGateThresholds thresholds) {
        boolean passed = metrics.maxDrawdownPct() <= thresholds.maxDrawdownPct();
        return GateCheckResult.numeric(
            "max_drawdown_pct",
            passed,
            passed
                ? "Max DD " + metrics.maxDrawdownPct() + "% <= " + thresholds.maxDrawdownPct() + "%"
                : "Max DD " + metrics.maxDrawdownPct() + "% exceeds " + thresholds.maxDrawdownPct() + "%",
            thresholds.maxDrawdownPct(),
            metrics.maxDrawdownPct());
    }

    static GateCheckResult minReturn(BacktestRunMetrics metrics, PromoteGateThresholds thresholds) {
        boolean passed = metrics.totalReturnPct() >= thresholds.minReturnPct();
        return GateCheckResult.numeric(
            "min_return_pct",
            passed,
            passed
                ? "Return " + metrics.totalReturnPct() + "% >= " + thresholds.minReturnPct() + "%"
                : "Return " + metrics.totalReturnPct() + "% below " + thresholds.minReturnPct() + "%",
            thresholds.minReturnPct(),
            metrics.totalReturnPct());
    }

    static GateCheckResult goldenBaseline(RunRecord run, PromoteGateThresholds thresholds) {
        Optional<GoldenBaselineProfiles.Profile> profile = GoldenBaselineProfiles.match(
            run.strategyId(),
            run.configSnapshot());
        if (profile.isEmpty()) {
            return new GateCheckResult(
                "golden_baseline",
                true,
                "No golden profile for this barsSource; metric gates apply");
        }
        GoldenBaselineProfiles.Profile golden = profile.get();
        BacktestRunMetrics metrics = BacktestRunMetrics.fromRun(run);
        boolean tradesOk = metrics.totalTrades() == golden.expectedTrades();
        boolean returnOk = GoldenBacktestBaseline.withinRelativeTolerance(
            metrics.totalReturnPct(), golden.expectedReturnPct(), golden.returnTolerancePct());
        boolean ddOk = GoldenBacktestBaseline.amountWithinRelativeTolerance(
            metrics.maxDrawdownPct(), golden.expectedMaxDrawdownPct(), golden.maxDrawdownTolerancePct());
        boolean passed = tradesOk && returnOk && ddOk;
        return GateCheckResult.numeric(
            "golden_baseline",
            passed,
            passed
                ? "Metrics match golden baseline"
                : "Golden mismatch: trades=" + metrics.totalTrades()
                    + " return=" + metrics.totalReturnPct() + "% maxDD=" + metrics.maxDrawdownPct() + "%",
            golden.expectedReturnPct(),
            metrics.totalReturnPct());
    }

    static GateCheckResult validationModule(
        ValidationContext context,
        PromoteGateThresholds thresholds,
        List<ValidationModule> modules
    ) {
        if (!thresholds.validationModuleEnabled()) {
            return new GateCheckResult("validation_module", true, "Validation module disabled");
        }
        if (modules.isEmpty()) {
            return new GateCheckResult(
                "validation_module",
                true,
                "Validation enabled but no module configs active — skipped");
        }
        for (ValidationModule module : modules) {
            Optional<GateCheckResult> result = module.evaluate(context);
            if (result.isPresent() && !result.get().passed()) {
                return result.get();
            }
        }
        return new GateCheckResult("validation_module", true, "Validation modules passed");
    }

    static GateCheckResult requirePaperDeployment(Optional<DeploymentRecord> current) {
        boolean passed = current.isPresent() && current.get().mode() == RunMode.PAPER;
        return new GateCheckResult(
            "paper_deployed",
            passed,
            passed ? "Strategy deployed to PAPER" : "Strategy not in PAPER mode");
    }

    static GateCheckResult ibkrCredentialsForPaper(
        ExecutionLabel paperLabel,
        String accountId,
        BrokerAccountRegistry registry
    ) {
        if (paperLabel != ExecutionLabel.PAPER_IBKR) {
            return new GateCheckResult("ibkr_credentials", true, "Not required for " + paperLabel.name());
        }
        boolean stub = ibkrUseStub();
        boolean configured = registry != null && registry.credentialsConfigured(accountId);
        boolean passed = stub || configured;
        return new GateCheckResult(
            "ibkr_credentials",
            passed,
            passed
                ? "IBKR credentials or IBKR_USE_STUB available for PAPER_IBKR"
                : "IBKR credentials required for PAPER_IBKR promote (or set IBKR_USE_STUB=true)");
    }

    static boolean ibkrUseStub() {
        return "true".equalsIgnoreCase(System.getenv("IBKR_USE_STUB"));
    }

    static GateCheckResult oandaCredentialsForPaper(ExecutionLabel paperLabel, boolean credentialsPresent) {
        if (paperLabel != ExecutionLabel.PAPER_OANDA) {
            return new GateCheckResult("oanda_credentials", true, "Not required for " + paperLabel.name());
        }
        return new GateCheckResult(
            "oanda_credentials",
            credentialsPresent,
            credentialsPresent
                ? "OANDA credentials available for PAPER_OANDA"
                : "OANDA credentials required for PAPER_OANDA promote");
    }

    static GateCheckResult brokerAccountKnown(String accountId, boolean known) {
        return new GateCheckResult(
            "broker_account",
            known,
            known
                ? "Broker account " + accountId + " configured"
                : "Unknown broker account: " + accountId);
    }

    static GateCheckResult paperExecutionLabel(Optional<DeploymentRecord> current) {
        if (current.isEmpty()) {
            return new GateCheckResult("paper_execution_label", false, "No paper deployment");
        }
        ExecutionLabel label = current.get().executionLabel();
        boolean passed = label.countsTowardPaperPeriod();
        String failureMessage = switch (label) {
            case PAPER_STUB -> "Stub does not count toward paper period (actual: " + label.name() + ")";
            case PAPER_IBKR -> "IBKR paper is broker-backed but does not count toward the 30-day LIVE gate "
                + "(MVP: promote via PAPER_OANDA for LIVE path; actual: " + label.name() + ")";
            default -> label.name() + " does not count toward paper period";
        };
        return new GateCheckResult(
            "paper_execution_label",
            passed,
            passed ? "Paper deployment on " + label.name() : failureMessage);
    }

    static GateCheckResult paperDuration(
        Optional<DeploymentRecord> current,
        PromoteGateThresholds thresholds,
        Clock clock
    ) {
        if (current.isEmpty()) {
            return GateCheckResult.numeric(
                "paper_duration_days",
                false,
                "No paper deployment",
                thresholds.paperDaysBeforeLive(),
                0.0);
        }
        if (!current.get().executionLabel().countsTowardPaperPeriod()) {
            return GateCheckResult.numeric(
                "paper_duration_days",
                false,
                "Stub does not count toward paper period",
                thresholds.paperDaysBeforeLive(),
                0.0);
        }
        long elapsedDays = ChronoUnit.DAYS.between(current.get().promotedAt(), Instant.now(clock));
        boolean passed = elapsedDays >= thresholds.paperDaysBeforeLive();
        return GateCheckResult.numeric(
            "paper_duration_days",
            passed,
            passed
                ? "Paper elapsed " + elapsedDays + " days >= " + thresholds.paperDaysBeforeLive()
                : "Paper elapsed " + elapsedDays + " days < " + thresholds.paperDaysBeforeLive(),
            thresholds.paperDaysBeforeLive(),
            (double) elapsedDays);
    }
}
