package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Locked OOS holdout validation gate (Story 19.4 / PS-GR5). */
public final class OosHoldoutValidationModule implements ValidationModule {

    public static final String GATE_NAME = "oos_holdout";

    private final OosHoldoutConfig config;

    public OosHoldoutValidationModule(OosHoldoutConfig config) {
        this.config = config != null ? config : OosHoldoutConfig.DEFAULT;
    }

    @Override
    public Optional<GateCheckResult> evaluate(ValidationContext context) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        RunRecord run = context.backtestRun();
        RunConfigSnapshot snapshot = RunConfigSnapshot.fromRecord(run);
        try {
            List<Bar> bars = loadBars(snapshot);
            BarHoldoutSplit split = BarHoldoutSplit.split(bars, config.holdoutPct(), config.minHoldoutBars());
            BacktestResult result = HoldoutBacktestRunner.runHoldout(snapshot, split.holdout());

            boolean ddPassed = result.maxDrawdownPct() <= config.maxDrawdownPct();
            boolean returnPassed = result.totalReturnPct() >= config.minReturnPct();
            boolean passed = ddPassed && returnPassed;

            appendValidationEvent(context, split, result, passed);

            String message = passed
                ? "OOS holdout passed: return " + fmt(result.totalReturnPct())
                    + "%, max DD " + fmt(result.maxDrawdownPct())
                    + "% on " + split.holdout().size() + " bars ("
                    + split.holdoutStart() + " → " + split.holdoutEnd() + ")"
                : "OOS holdout failed: return " + fmt(result.totalReturnPct())
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
                "OOS holdout evaluation failed: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            return Optional.of(new GateCheckResult(
                GATE_NAME,
                false,
                "OOS holdout split failed: " + e.getMessage()));
        } catch (RuntimeException e) {
            return Optional.of(new GateCheckResult(
                GATE_NAME,
                false,
                "OOS holdout evaluation failed: " + e.getMessage()));
        }
    }

    private static List<Bar> loadBars(RunConfigSnapshot snapshot) throws IOException {
        return ValidationBarLoader.load(snapshot);
    }

    private void appendValidationEvent(
        ValidationContext context,
        BarHoldoutSplit split,
        BacktestResult result,
        boolean passed
    ) {
        RunRecord run = context.backtestRun();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("validationType", "OOS_HOLDOUT");
        payload.put("passed", passed);
        payload.put("holdout", split.toMap());
        payload.put("holdoutPctConfigured", config.holdoutPct());
        payload.put("validationConfigSnapshot", split.validationConfigSnapshot(
            run.configHash(), config.holdoutPct()));
        payload.put("totalTrades", result.totalTrades());
        payload.put("totalReturnPct", result.totalReturnPct());
        payload.put("maxDrawdownPct", result.maxDrawdownPct());
        payload.put("thresholds", Map.of(
            "maxDrawdownPct", config.maxDrawdownPct(),
            "minReturnPct", config.minReturnPct()));
        payload.put("dataSource", "LOCKED_HOLDOUT");
        context.journalOperatorAction(Map.copyOf(payload));
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
