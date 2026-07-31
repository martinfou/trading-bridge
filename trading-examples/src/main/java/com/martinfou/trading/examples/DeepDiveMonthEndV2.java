package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.util.List;

/**
 * Deep dive V2 — TurnOfMonthFlowStrategy conceptual variation.
 *
 * Bug fixes applied (from DeepDiveTurnOfMonthFix):
 *   1. tradeCountThisMonth reset on month change (was: 1 trade in 20 years)
 *   2. UTC for calendar day counting (was: America/New_York)
 *
 * Conceptual variation: instead of "entry on FIRST bar of window only"
 * (which restricted entries to the 00:00 UTC bar of the first window day —
 * a low-liquidity slot), allow entry on ANY bar during the 3-day month-end
 * window, capped at 1 trade per month.
 *
 * Run: java -cp ... com.martinfou.trading.examples.DeepDiveMonthEndV2 <Symbol> <YearRange>
 */
public class DeepDiveMonthEndV2 {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.println("== DeepDive MonthEnd V2 (full-window entry) ==");
        System.out.println("Symbol=" + symbol + " bars=" + bars.size() + " range=" + yearSpec);

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        var strategy = new TurnOfMonthFlowWindowStrategy("MonthEndV2", symbol);
        var ctx = RunContext.forStrategy(
            null, "MonthEndV2", strategy, symbol,
            RunMode.BACKTEST, bars, capital, null, cost);
        ctx.run().printSummary();
    }
}
