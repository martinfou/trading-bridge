package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.longterm.LtDoubleMA;

import java.util.ArrayList;
import java.util.List;

/**
 * RunLtDoubleMA — Backtest runner for LtDoubleMA (Golden Cross / Death Cross).
 *
 * Backtests: EUR_USD FULL (2006-2025), IS (2006-2015), OOS1 (2016-2020), OOS2 (2021-2025) + GBP_USD FULL
 */
public class RunLtDoubleMA {

    private static final double CAPITAL = LotSizing.DEFAULT_STARTING_CAPITAL;

    public static void main(String[] args) throws Exception {
        System.out.println("=" .repeat(90));
        System.out.println("  LtDoubleMA — Golden Cross / Death Cross Backtest Suite");
        System.out.println("  EMA(50) / SMA(200) crossover · SL=2×ATR(14) · TP=4×ATR(14)");
        System.out.println("  Capital: $" + String.format("%,.0f", CAPITAL));
        System.out.println("=" .repeat(90));

        // ── EUR_USD ──────────────────────────────────────────────────────────
        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — Full Range (2006-2025)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2006-2025");

        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — In-Sample (2006-2015)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2006-2015");

        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — OOS1 (2016-2020)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2016-2020");

        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — OOS2 (2021-2025)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2021-2025");

        // ── GBP_USD ──────────────────────────────────────────────────────────
        System.out.println("\n" + "─".repeat(90));
        System.out.println("  GBP_USD — Full Range (2006-2025)");
        System.out.println("─".repeat(90));
        runSymbol("GBP_USD", "2006-2025");

        System.out.println("\n" + "=" .repeat(90));
        System.out.println("  ALL BACKTESTS COMPLETE");
        System.out.println("=" .repeat(90));
    }

    private static void runSymbol(String symbol, String yearSpec) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.println("  Data: " + loaded.source() + " (" + bars.size() + " bars)");

        if (bars.isEmpty()) {
            System.out.println("  ⚠ No data loaded, skipping.");
            return;
        }

        String strategyName = "LtDoubleMA_" + symbol.replace("_", "") + "_" + yearSpec.replace("-", "");
        var strategy = new LtDoubleMA(strategyName, symbol);
        var context = RunContext.forStrategy(strategy, symbol, com.martinfou.trading.backtest.RunMode.BACKTEST, bars, CAPITAL);
        BacktestResult result = context.run();

        result.printSummary();

        // Print key metrics for report
        System.out.println();
        System.out.println("  Key Metrics:");
        System.out.println("    Total Trades : " + result.totalTrades());
        if (result.totalTrades() > 0) {
            System.out.println("    Win Rate     : " + String.format("%.1f%%", result.winRatePct()));
            System.out.println("    Net Profit   : $" + String.format("%,.2f", result.totalPnl()));
            System.out.println("    Max Drawdown : " + String.format("%.2f%%", result.maxDrawdownPct()));
            System.out.println("    Sharpe       : " + String.format("%.2f", result.sharpeRatio()));
            System.out.println("    Profit Factor: " + String.format("%.2f", result.profitFactor()));
        }
    }
}
