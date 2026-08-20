package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.SeasonalCalendarStrategy;

import java.util.List;

/**
 * RunAprilCalendar — Backtest SeasonalCalendarStrategy (miroir haussier AVRIL
 * BUY + calendrier long/short complet BUY Apr / SELL May+Aug) AVEC coûts
 * (commission $0.07 + slippage 0.01%).
 *
 * Mode 1 (--april, défaut) : AVRIL BUY seul, multi-paires 2006-2026.
 * Mode 2 (--calendar)     : calendrier complet BUY Apr + SELL May+Aug, multi-paires.
 * Mode 3 (--wf)           : walk-forward IS 2006-2015 / OOS 2016-2026 (GBP, EUR).
 * Mode 4 (--regime)       : bull 2006-2012 / bear 2013-2015 / bull2 2016-2026 (GBP).
 * Mode 5 (--sweep)        : sweep offsets entrée/sortie {0,3,5,7} × {0,3,5,7} (GBP).
 * Mode 6 (--price)        : sépare PnL prix vs swap (GBP).
 * Mode 7 (--verify)       : reproduit BearishMonthsFade MAY+AUG (validation implémentation).
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunAprilCalendar
 *   java -cp "$CP" com.martinfou.trading.examples.RunAprilCalendar --calendar
 *   java -cp "$CP" com.martinfou.trading.examples.RunAprilCalendar --wf
 *   java -cp "$CP" com.martinfou.trading.examples.RunAprilCalendar --regime
 *   java -cp "$CP" com.martinfou.trading.examples.RunAprilCalendar --sweep
 *   java -cp "$CP" com.martinfou.trading.examples.RunAprilCalendar --price
 *   java -cp "$CP" com.martinfou.trading.examples.RunAprilCalendar --verify
 */
public class RunAprilCalendar {

    static final double CAPITAL = 50_000;
    static final String[] PAIRS = {"GBP_USD", "EUR_USD", "AUD_USD", "NZD_USD", "USD_JPY", "USD_CHF"};

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0) {
            switch (args[0]) {
                case "--calendar" -> { runCalendar(cost); return; }
                case "--wf"       -> { runWalkForward(cost); return; }
                case "--regime"   -> { runRegime(cost); return; }
                case "--sweep"    -> { runSweep(cost); return; }
                case "--price"    -> { runPriceVsSwap(cost); return; }
                case "--verify"   -> { runVerify(cost); return; }
                default -> { /* default = --april */ }
            }
        }
        runApril(cost);
    }

    /** Mode 1 : AVRIL BUY seul, multi-paires. */
    private static void runApril(BacktestExecutionCost cost) throws Exception {
        System.out.println("==================================================");
        System.out.println("SEASONAL CALENDAR — AVRIL BUY seul (miroir haussier), H1");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("==================================================");
        printHeader();
        for (String symbol : PAIRS) {
            runOne(cost, symbol, "2006-2026",
                SeasonalCalendarStrategy.DEFAULT_WINDOW_MONTHS,
                SeasonalCalendarStrategy.DEFAULT_DIRECTIONS, "APRIL BUY");
        }
        System.out.println("\nDONE");
    }

    /** Mode 2 : calendrier long/short complet BUY Apr + SELL May+Aug. */
    private static void runCalendar(BacktestExecutionCost cost) throws Exception {
        System.out.println("==================================================");
        System.out.println("SEASONAL CALENDAR — COMPLET BUY Apr + SELL May+Aug, H1");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("==================================================");
        printHeader();
        for (String symbol : PAIRS) {
            runOne(cost, symbol, "2006-2026",
                SeasonalCalendarStrategy.FULL_CALENDAR_MONTHS,
                SeasonalCalendarStrategy.FULL_CALENDAR_DIRECTIONS, "CALENDAR 4+5+8");
        }
        System.out.println("\nDONE");
    }

    /** Mode 3 : walk-forward IS 2006-2015 / OOS 2016-2026. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String[] symbols = {"GBP_USD", "EUR_USD"};
        int[][][] configs = new int[][][]{
            {SeasonalCalendarStrategy.DEFAULT_WINDOW_MONTHS},
            {SeasonalCalendarStrategy.FULL_CALENDAR_MONTHS}
        };
        String[] labels = {"AVRIL BUY seul", "CALENDRIER 4+5+8"};

        for (int c = 0; c < configs.length; c++) {
            Order.Side[] sides = (c == 0)
                ? SeasonalCalendarStrategy.DEFAULT_DIRECTIONS
                : SeasonalCalendarStrategy.FULL_CALENDAR_DIRECTIONS;
            for (String symbol : symbols) {
                System.out.println("=== WALK-FORWARD " + symbol + " " + labels[c]
                    + " (IS 2006-2015 / OOS 2016-2026) ===");
                System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-10s%n",
                    "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
                for (var phase : new String[][]{{"IS", "2006-2015"}, {"OOS", "2016-2026"}}) {
                    var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, phase[1]);
                    List<Bar> bars = loaded.bars();
                    var strategy = new SeasonalCalendarStrategy("SeasonalCalendar", symbol,
                        configs[c][0], sides);
                    BacktestResult r = RunContext.forStrategy(null, "SeasonalCalendar", strategy,
                        symbol, RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                    System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                        phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                        r.totalTrades(), r.totalPnl(), r.totalReturnPct());
                }
                System.out.println();
            }
        }
        System.out.println("DONE");
    }

    /** Mode 4 : régime de marché sur GBP (bull/bear/bull2). */
    private static void runRegime(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        String[] regimes = {"2006-2012", "2013-2015", "2016-2026"};
        System.out.println("=== RÉGIME GBP_USD (bull 2006-12 / bear 2013-15 / bull2 2016-26) ===");
        for (var cfg : new int[][][]{
            {SeasonalCalendarStrategy.DEFAULT_WINDOW_MONTHS},
            {SeasonalCalendarStrategy.FULL_CALENDAR_MONTHS}
        }) {
            Order.Side[] sides = (cfg[0].length == 1)
                ? SeasonalCalendarStrategy.DEFAULT_DIRECTIONS
                : SeasonalCalendarStrategy.FULL_CALENDAR_DIRECTIONS;
            String label = (cfg[0].length == 1) ? "AVRIL BUY" : "CALENDRIER 4+5+8";
            System.out.println("--- " + label + " ---");
            System.out.printf("%-10s %-6s %-6s %-6s %-7s %-12s %-10s%n",
                "REGIME", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
            for (String spec : regimes) {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, spec);
                List<Bar> bars = loaded.bars();
                var strategy = new SeasonalCalendarStrategy("SeasonalCalendar", symbol,
                    cfg[0], sides);
                BacktestResult r = RunContext.forStrategy(null, "SeasonalCalendar", strategy,
                    symbol, RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                    spec, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
            System.out.println();
        }
        System.out.println("DONE");
    }

    /** Mode 5 : sweep robustesse offsets {0,3,5,7} (plateau vs pic). */
    private static void runSweep(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        int[] offsets = {0, 3, 5, 7};
        System.out.println("=== SWEEP ROBUSTESSE GBP_USD AVRIL BUY (entrée 1+o / sortie fin-o) ===");
        System.out.printf("%-12s %-12s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "ENTRY_OFS", "EXIT_OFS", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (int e : offsets) {
            for (int x : offsets) {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
                List<Bar> bars = loaded.bars();
                var strategy = new SeasonalCalendarStrategy("SeasonalCalendar", symbol,
                    SeasonalCalendarStrategy.DEFAULT_WINDOW_MONTHS,
                    SeasonalCalendarStrategy.DEFAULT_DIRECTIONS, e, x);
                BacktestResult r = RunContext.forStrategy(null, "SeasonalCalendar", strategy,
                    symbol, RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-12d %-12d %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                    e, x, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }

    /** Mode 6 : PnL prix vs swap (artefact taux constants 2024-26). */
    private static void runPriceVsSwap(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
        List<Bar> bars = loaded.bars();
        var strategy = new SeasonalCalendarStrategy("SeasonalCalendar", symbol);
        BacktestResult r = RunContext.forStrategy(null, "SeasonalCalendar", strategy,
            symbol, RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.println("=== GBP_USD — AVRIL BUY : PnL prix vs swap (2006-2026, coûts inclus) ===");
        System.out.printf("PF=%.2f WR=%.1f%% DD=%.2f%% trades=%d%n",
            r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades());
        System.out.printf("Net total (swap+comm inclus): $%,.2f%n", r.totalPnl());
        System.out.printf("Swap total: $%,.2f  | Commission: $%,.2f%n", r.totalSwap(), r.totalCommission());
        double pricePnl = r.totalPnl() - r.totalSwap() - r.totalCommission();
        System.out.printf("PnL prix seul (≈ net - swap - comm): $%,.2f%n", pricePnl);
        System.out.println("\nDONE");
    }

    /** Mode 7 : validation d'implémentation — reproduire BearishMonthsFade MAY+AUG. */
    private static void runVerify(BacktestExecutionCost cost) throws Exception {
        String[] symbols = {"GBP_USD", "EUR_USD"};
        int[] mayAug = {5, 8};
        Order.Side[] sellSides = {Order.Side.SELL, Order.Side.SELL};
        System.out.println("=== VERIFY — SELL May+Aug via SeasonalCalendar (doit ≈ BearishMonthsFade) ===");
        printHeader();
        for (String symbol : symbols) {
            runOne(cost, symbol, "2006-2026", mayAug, sellSides, "MAY+AUG SELL");
        }
        System.out.println("\nDONE");
    }

    private static void runOne(BacktestExecutionCost cost, String symbol, String years,
                               int[] months, Order.Side[] sides, String label) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, years);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); return; }
        var strategy = new SeasonalCalendarStrategy("SeasonalCalendar", symbol, months, sides);
        BacktestResult r = RunContext.forStrategy(null, "SeasonalCalendar", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.printf("%-8s %-14s PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d "
                + "net=$%10.2f ret=%-7.2f%% swap=$%8.2f comm=$%8.2f%n",
            symbol, label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap(), r.totalCommission());
    }

    private static void printHeader() {
        System.out.printf("%-8s %-14s %-8s %-8s %-8s %-8s %-14s %-10s %-10s %-10s%n",
            "SYMBOL", "WINDOW", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$", "COMM$");
    }
}
