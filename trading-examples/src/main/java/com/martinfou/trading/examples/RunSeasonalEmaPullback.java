package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.SeasonalEmaPullbackContinuation;
import com.martinfou.trading.strategies.prop.EmaPullbackContinuationStrategy;

import java.util.List;

/**
 * Backtest SeasonalEmaPullbackContinuation vs EmaPullbackContinuation (baseline)
 * AVEC coûts (commission $0.07 + slippage 0.01%) — jamais sans coûts.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunSeasonalEmaPullback EUR_USD 2006-2026
 */
public class RunSeasonalEmaPullback {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.printf("%n=== %s (%d bars) — avec coûts $0.07 + 0.01%% slippage ===%n%n",
            symbol, bars.size());

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.println("--- BASELINE: EmaPullbackContinuation ---");
        var base = new EmaPullbackContinuationStrategy(symbol);
        RunContext.forStrategy(null, "EmaPullbackContinuation", base, symbol,
            RunMode.BACKTEST, bars, capital, null, cost).run().printSummary();

        System.out.println();
        System.out.println("--- VARIATION: SeasonalEmaPullbackContinuation ---");
        var seasonal = new SeasonalEmaPullbackContinuation(symbol);
        RunContext.forStrategy(null, "SeasonalEmaPullback", seasonal, symbol,
            RunMode.BACKTEST, bars, capital, null, cost).run().printSummary();
    }
}
