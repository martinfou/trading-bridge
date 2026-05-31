package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stress execution gate with degraded slippage/commission (Story 19.5 / PS-GR6). */
public final class ExecutionStressValidationModule implements ValidationModule {

    public static final String GATE_NAME = "execution_stress";

    private final ExecutionStressConfig config;

    public ExecutionStressValidationModule(ExecutionStressConfig config) {
        this.config = config != null ? config : ExecutionStressConfig.DEFAULT;
    }

    @Override
    public Optional<GateCheckResult> evaluate(ValidationContext context) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        RunRecord run = context.backtestRun();
        RunConfigSnapshot snapshot = RunConfigSnapshot.fromRecord(run);
        BacktestExecutionCost baselineCost = snapshot.executionCost();
        BacktestExecutionCost stressCost = config.stressCost(baselineCost);
        try {
            List<Bar> bars = ValidationBarLoader.load(snapshot);
            BacktestResult result = ValidationBacktestRunner.run(snapshot, bars, stressCost);

            boolean ddPassed = result.maxDrawdownPct() <= config.maxDrawdownPct();
            boolean returnPassed = result.totalReturnPct() >= config.minReturnPct();
            boolean passed = ddPassed && returnPassed;

            appendValidationEvent(context, baselineCost, stressCost, bars.size(), result, passed);

            String message = passed
                ? "Execution stress passed: return " + fmt(result.totalReturnPct())
                    + "%, max DD " + fmt(result.maxDrawdownPct())
                    + "% under stress slippage x" + fmt(config.slippageMultiplier())
                    + " commission x" + fmt(config.commissionMultiplier())
                : "Execution stress failed: return " + fmt(result.totalReturnPct())
                    + "% (min " + fmt(config.minReturnPct()) + "%), max DD "
                    + fmt(result.maxDrawdownPct()) + "% (max " + fmt(config.maxDrawdownPct()) + "%)";

            return Optional.of(new GateCheckResult(
                GATE_NAME,
                passed,
                message,
                config.maxDrawdownPct(),
                result.maxDrawdownPct()));
        } catch (IOException e) {
            return Optional.of(new GateCheckResult(
                GATE_NAME,
                false,
                "Execution stress evaluation failed: " + e.getMessage()));
        } catch (RuntimeException e) {
            return Optional.of(new GateCheckResult(
                GATE_NAME,
                false,
                "Execution stress evaluation failed: " + e.getMessage()));
        }
    }

    private Map<String, Object> validationConfigSnapshot(
        RunRecord run,
        int barCount,
        BacktestExecutionCost baselineCost,
        BacktestExecutionCost stressCost
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceConfigHash", run.configHash());
        map.put("barCount", barCount);
        map.put("baselineExecutionCost", baselineCost.toMap());
        map.put("stressExecutionCost", stressCost.toMap());
        map.put("slippageMultiplier", config.slippageMultiplier());
        map.put("commissionMultiplier", config.commissionMultiplier());
        return Map.copyOf(map);
    }

    private void appendValidationEvent(
        ValidationContext context,
        BacktestExecutionCost baselineCost,
        BacktestExecutionCost stressCost,
        int barCount,
        BacktestResult result,
        boolean passed
    ) {
        RunRecord run = context.backtestRun();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("validationType", "EXECUTION_STRESS");
        payload.put("passed", passed);
        payload.put("barCount", barCount);
        payload.put("baselineExecutionCost", baselineCost.toMap());
        payload.put("stressExecutionCost", stressCost.toMap());
        payload.put("stressProfile", config.toMap());
        payload.put("validationConfigSnapshot", validationConfigSnapshot(
            run, barCount, baselineCost, stressCost));
        payload.put("totalTrades", result.totalTrades());
        payload.put("totalReturnPct", result.totalReturnPct());
        payload.put("maxDrawdownPct", result.maxDrawdownPct());
        payload.put("totalCommission", result.totalCommission());
        payload.put("totalSlippage", result.totalSlippage());
        payload.put("thresholds", Map.of(
            "maxDrawdownPct", config.maxDrawdownPct(),
            "minReturnPct", config.minReturnPct()));
        context.journalOperatorAction(Map.copyOf(payload));
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
