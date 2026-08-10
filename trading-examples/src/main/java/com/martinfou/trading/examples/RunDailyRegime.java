package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.DailyRegimeMomentum;

import java.util.List;

/**
 * Backtest DailyRegimeMomentum AVEC coûts (commission $0.07 + slippage 0.01%).
 * Multi-paires + sweep paramétrique ±20% sur la fenêtre de momentum.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunDailyRegime EUR_USD 2006-2026
 */
public class RunDailyRegime {

    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.printf("%n=== DailyRegimeMomentum %s (%d bars) — coûts $0.07 + 0.01%% slip ===%n%n",
            symbol, bars.size());

        // Baseline params
        var base = new DailyRegimeMomentum("DailyRegimeMomentum", symbol);
        RunContext.forStrategy(null, "DailyRegimeMomentum", base, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run().printSummary();
    }
}
