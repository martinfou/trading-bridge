package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.TurnOfMonthFlowStrategy;

import java.util.List;

/**
 * Deep dive — TurnOfMonthFlowStrategy timezone fix validation.
 *
 * Compares the strategy behavior (1) as-is (America/New_York day counting,
 * the documented calendar-strategy timezone bug) and (2) after switching
 * day counting to ZoneOffset.UTC.
 *
 * Run: java -cp ... com.martinfou.trading.examples.DeepDiveTurnOfMonthFix <Symbol> <YearRange>
 * Example: ... DeepDiveTurnOfMonthFix EUR_USD 2006-2026
 */
public class DeepDiveTurnOfMonthFix {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.println("== DeepDive TurnOfMonth timezone fix ==");
        System.out.println("Symbol=" + symbol + " bars=" + bars.size() + " range=" + yearSpec);
        System.out.println("First bar: " + bars.get(0).timestamp() + "  Last bar: " + bars.get(bars.size()-1).timestamp());

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        // 1) As-is (America/New_York day counting) — the documented bug
        var strategyNY = new TurnOfMonthFlowStrategy("TurnOfMonth_NY", symbol);
        var ctxNY = RunContext.forStrategy(
            null, "TurnOfMonth_NY", strategyNY, symbol,
            RunMode.BACKTEST, bars, capital, null, cost);
        System.out.println("\n--- AS-IS (America/New_York day counting) ---");
        ctxNY.run().printSummary();

        // 2) Fixed (UTC day counting) — see patched class
        var strategyUTC = new TurnOfMonthFlowStrategy("TurnOfMonth_UTC", symbol);
        var ctxUTC = RunContext.forStrategy(
            null, "TurnOfMonth_UTC", strategyUTC, symbol,
            RunMode.BACKTEST, bars, capital, null, cost);
        System.out.println("\n--- FIXED (UTC day counting) ---");
        ctxUTC.run().printSummary();
    }
}
