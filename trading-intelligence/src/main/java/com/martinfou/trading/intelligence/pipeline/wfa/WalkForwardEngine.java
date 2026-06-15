package com.martinfou.trading.intelligence.pipeline.wfa;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Walk-Forward Analysis engine.
 *
 * Splits historical data into rolling IS/OOS windows, runs a strategy
 * on each, and merges OOS results into a single performance summary.
 *
 * Usage:
 * <pre>
 * var engine = new WalkForwardEngine(new WfaConfig());
 * WfaResult result = engine.run(strategy, allBars, "EUR_USD");
 * </pre>
 */
public class WalkForwardEngine {

    private static final double CAPITAL = 10_000;
    private static final double MIN_OOS_PF = 1.0;
    private static final double MAX_PF_DEGRADATION = 0.5;

    private final WalkForwardSplitter splitter;
    private final BacktestExecutionCost costs;

    public WalkForwardEngine() {
        this(new WfaConfig(), BacktestExecutionCost.ZERO);
    }

    public WalkForwardEngine(WfaConfig config, BacktestExecutionCost costs) {
        this.splitter = new WalkForwardSplitter(config);
        this.costs = costs;
    }

    /**
     * Run WFA on a strategy with full historical bars.
     */
    public WfaResult run(Strategy strategy, List<Bar> allBars, String symbol) {
        List<WfaWindow> windows = splitter.split(allBars);

        if (windows.isEmpty()) {
            return new WfaResult(List.of(), 0, 0, 0, 0, 0, false);
        }

        List<WfaWindowResult> windowResults = new ArrayList<>();
        List<Bar> mergedOosBars = new ArrayList<>();
        double cumulativeOosPnl = 0;
        int totalOosTrades = 0;

        for (WfaWindow window : windows) {
            // IS backtest
            Strategy isStrategy = recreateStrategy(strategy, symbol);
            BacktestResult isResult = runBacktest(isStrategy, window.inSampleBars(), symbol);
            if (isResult.totalTrades() < 10) continue;

            // OOS backtest
            Strategy oosStrategy = recreateStrategy(strategy, symbol);
            BacktestResult oosResult = runBacktest(oosStrategy, window.outOfSampleBars(), symbol);

            // Track merged OOS metrics
            cumulativeOosPnl += oosResult.totalPnl();
            totalOosTrades += oosResult.totalTrades();
            mergedOosBars.addAll(window.outOfSampleBars());

            windowResults.add(new WfaWindowResult(
                window.index(),
                isResult.profitFactor(), isResult.sharpeRatio(),
                isResult.totalReturnPct(), isResult.maxDrawdownPct(), isResult.totalTrades(),
                oosResult.profitFactor(), oosResult.sharpeRatio(),
                oosResult.totalReturnPct(), oosResult.maxDrawdownPct(), oosResult.totalTrades()
            ));
        }

        if (windowResults.isEmpty()) {
            return new WfaResult(List.of(), 0, 0, 0, 0, 0, false);
        }

        // Aggregate OOS metrics
        double mergedPf = windowResults.stream()
            .mapToDouble(WfaWindowResult::oosProfitFactor)
            .filter(v -> v > 0)
            .average().orElse(0);
        double mergedSharpe = windowResults.stream()
            .mapToDouble(WfaWindowResult::oosSharpe)
            .average().orElse(0);
        double mergedDd = windowResults.stream()
            .mapToDouble(WfaWindowResult::oosMaxDd)
            .max().orElse(100);
        double mergedReturn = windowResults.stream()
            .mapToDouble(WfaWindowResult::oosReturnPct)
            .average().orElse(0);

        // Pass criteria: average OOS PF >= 1.0 AND degradation < 50%
        boolean passed = mergedPf >= MIN_OOS_PF
            && windowResults.stream().allMatch(w -> w.pfDegradation() >= MAX_PF_DEGRADATION);

        return new WfaResult(windowResults, mergedPf, mergedSharpe,
            mergedReturn, mergedDd, totalOosTrades, passed);
    }

    private BacktestResult runBacktest(Strategy strategy, List<Bar> bars, String symbol) {
        RunContext context = RunContext.forStrategy(strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL);
        return context.run();
    }

    private Strategy recreateStrategy(Strategy original, String symbol) {
        try {
            return original.getClass()
                .getConstructor(String.class, String.class)
                .newInstance(original.name(), symbol);
        } catch (Exception e) {
            throw new RuntimeException("Cannot recreate strategy: " + original.name(), e);
        }
    }
}
