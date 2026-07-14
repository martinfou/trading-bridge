package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.*;
import com.martinfou.trading.core.*;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.longterm.LtRangeBreakout;

import java.io.IOException;
import java.util.*;

/**
 * Runs LtRangeBreakout (Donchian Channel Breakout) across multiple
 * forex pairs and periods for walk-forward validation.
 *
 * Backtests:
 *   - EUR_USD  Full (2006-2025)  → IS (2006-2017) + OOS1 (2018-2023) + OOS2 (2024-2025)
 *   - GBP_USD  Full (2006-2025)
 */
public class RunLtRangeBreakout {

    private static final double DEFAULT_CAPITAL = 100_000.0;
    private static final double COMMISSION_PER_TRADE = 5.0;   // $5 per round-turn (~0.5 pip on 10k)
    private static final double SLIPPAGE_PCT = 0.0001;        // 1 pip

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(100));
        System.out.println("  LT RANGE BREAKOUT — Donchian Channel Breakout (Classic Turtle)");
        System.out.println("  Walk-Forward Validation");
        System.out.println("=".repeat(100));

        BacktestExecutionCost costs = new BacktestExecutionCost(COMMISSION_PER_TRADE, 0.0, SLIPPAGE_PCT, 0.0, 0.0);

        // ────────────── EUR_USD Walk-Forward ──────────────
        System.out.println("\n─── EUR_USD Walk-Forward ───");
        List<Bar> eurFull = loadBars("EUR_USD", "2006-2025");
        List<Bar> eurIS   = loadBars("EUR_USD", "2006-2017");
        List<Bar> eurOOS1 = loadBars("EUR_USD", "2018-2023");
        List<Bar> eurOOS2 = loadBars("EUR_USD", "2024-2025");

        runBacktest("LtRangeBreakout_EUR_USD_Full",  "EUR_USD", eurFull, DEFAULT_CAPITAL, costs);
        runBacktest("LtRangeBreakout_EUR_USD_IS",    "EUR_USD", eurIS,   DEFAULT_CAPITAL, costs);
        runBacktest("LtRangeBreakout_EUR_USD_OOS1",  "EUR_USD", eurOOS1, DEFAULT_CAPITAL, costs);
        runBacktest("LtRangeBreakout_EUR_USD_OOS2",  "EUR_USD", eurOOS2, DEFAULT_CAPITAL, costs);

        // ────────────── GBP_USD Full ──────────────
        System.out.println("\n─── GBP_USD Full ───");
        List<Bar> gbpFull = loadBars("GBP_USD", "2006-2025");
        runBacktest("LtRangeBreakout_GBP_USD_Full",  "GBP_USD", gbpFull, DEFAULT_CAPITAL, costs);
    }

    private static List<Bar> loadBars(String symbol, String yearSpec) {
        try {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
            List<Bar> bars = loaded.bars();
            System.out.printf("  Loaded %s %s: %d bars%n", symbol, yearSpec, bars.size());
            return bars;
        } catch (IOException e) {
            System.err.printf("  ✗ Failed to load %s %s: %s%n", symbol, yearSpec, e.getMessage());
            return List.of();
        }
    }

    private static void runBacktest(String name, String symbol, List<Bar> bars, double capital,
                                     BacktestExecutionCost costs) {
        if (bars.isEmpty()) {
            System.out.printf("  ⚠ %s: no data, skipping%n", name);
            return;
        }

        try {
            LtRangeBreakout strategy = new LtRangeBreakout(name, symbol);
            BacktestResult result = RunContext.forStrategy(
                null, null, strategy, symbol, RunMode.BACKTEST, bars, capital, null, costs
            ).run();
            result.printSummary();
        } catch (Exception e) {
            System.err.printf("  ✗ %s: backtest failed — %s%n", name, e.getMessage());
            e.printStackTrace();
        }
    }
}
