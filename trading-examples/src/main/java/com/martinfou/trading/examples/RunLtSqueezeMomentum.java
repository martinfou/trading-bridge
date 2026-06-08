package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.longterm.LtSqueezeMomentum;

import java.util.List;

public class RunLtSqueezeMomentum {
    private static final double CAPITAL = LotSizing.DEFAULT_STARTING_CAPITAL;

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(90));
        System.out.println("  LtSqueezeMomentum — Bollinger Squeeze + RSI Momentum");
        System.out.println("  BB(20,2) squeeze · RSI(14) direction · SL=2×ATR · TP=4×ATR");
        System.out.println("=".repeat(90));

        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — Full (2006-2026)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2006-2026");
        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — IS (2006-2015)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2006-2015");
        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — OOS1 (2016-2020)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2016-2020");
        System.out.println("\n" + "─".repeat(90));
        System.out.println("  EUR_USD — OOS2 (2021-2026)");
        System.out.println("─".repeat(90));
        runSymbol("EUR_USD", "2021-2026");
        System.out.println("\n" + "─".repeat(90));
        System.out.println("  GBP_USD — Full (2006-2026)");
        System.out.println("─".repeat(90));
        runSymbol("GBP_USD", "2006-2026");

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  ALL BACKTESTS COMPLETE");
        System.out.println("=".repeat(90));
    }

    private static void runSymbol(String symbol, String yearSpec) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.println("  Data: " + loaded.source() + " (" + bars.size() + " bars)");
        if (bars.isEmpty()) { System.out.println("  ⚠ No data"); return; }

        String name = "LtSqueezeMomentum_" + symbol.replace("_", "") + "_" + yearSpec.replace("-", "");
        var strategy = new LtSqueezeMomentum(name, symbol);
        var context = RunContext.forStrategy(strategy, symbol, com.martinfou.trading.backtest.RunMode.BACKTEST, bars, CAPITAL);
        BacktestResult result = context.run();
        result.printSummary();
        System.out.println();
        if (result.totalTrades() > 0) {
            System.out.println("  Key Metrics:");
            System.out.println("    Trades       : " + result.totalTrades());
            System.out.println("    Win Rate     : " + String.format("%.1f%%", result.winRatePct()));
            System.out.println("    Net PnL      : $" + String.format("%,.2f", result.totalPnl()));
            System.out.println("    Max DD       : " + String.format("%.2f%%", result.maxDrawdownPct()));
            System.out.println("    Sharpe       : " + String.format("%.2f", result.sharpeRatio()));
            System.out.println("    PF           : " + String.format("%.2f", result.profitFactor()));
        }
    }
}
