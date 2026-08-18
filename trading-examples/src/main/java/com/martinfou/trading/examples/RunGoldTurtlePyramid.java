package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtlePyramidStrategy;

import java.util.List;

/**
 * RunGoldTurtlePyramid — Backtest GoldTurtlePyramidStrategy (Turtle Donchian
 * 55/20 + pyramiding ½ ATR, XAU/USD H1) AVEC coûts ($0.07 + 0.01% slippage).
 *
 * Mode 1 (par défaut) : XAU_USD + contrôle EUR_USD (même mécanique, FX) pour
 *   vérifier que le pyramiding amplifie l'edge INSTRUMENT (or) et pas la
 *   mécanique.
 * Mode 2 (--baseline) : maxUnits=1 = GoldTurtleTrend pur (référence hier).
 * Mode 3 (--sweep) : maxUnits 1-4 × step 0.25/0.5/1.0 sur XAU_USD (12 cellules,
 *   plateau vs pic — anti-curve-fitting).
 * Mode 4 (--wf) : walk-forward IS 2006-2015 / OOS 2016-2025.
 * Mode 5 (--regime) : bull 2006-12 / bear 2013-15 / bull2 2016-25.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtlePyramid [--baseline|--sweep|--wf|--regime]
 */
public class RunGoldTurtlePyramid {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String[] PAIRS = {"XAU_USD", "EUR_USD"};
    static final double QTY = 10; // oz par unité — identique à GoldTurtleTrend

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--baseline")) { runBaseline(cost); return; }
        if (args.length > 0 && args[0].equals("--sweep"))    { runSweep(cost); return; }
        if (args.length > 0 && args[0].equals("--wf"))       { runWalkForward(cost, unitsFromArgs(args)); return; }
        if (args.length > 0 && args[0].equals("--regime"))   { runRegime(cost, unitsFromArgs(args)); return; }

        System.out.println("==================================================");
        System.out.println("GOLD TURTLE PYRAMID — Donchian 55/20 + ½ ATR, XAU H1");
        System.out.println("Pyramiding: maxUnits=4, step=0.5, qty=10oz/unité");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $50K");
        System.out.println("==================================================");

        for (String symbol : PAIRS) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
            var strategy = new GoldTurtlePyramidStrategy("GoldTurtlePyramid", symbol,
                55, 20, 20, 0.5, 4, QTY);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtlePyramid", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-8s bars=%-6d PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d "
                    + "net=$%10.2f ret=%-7.2f%% swap=$%8.2f comm=$%8.2f%n",
                symbol, bars.size(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap(), r.totalCommission());
        }
        System.out.println("\nDONE");
    }

    /** maxUnits=1 → équivalent GoldTurtleTrend (validation que la mécanique est préservée). */
    private static void runBaseline(BacktestExecutionCost cost) throws Exception {
        System.out.println("=== BASELINE maxUnits=1 (≡ GoldTurtleTrend hier) ===");
        System.out.printf("%-8s %-6s %-6s %-6s %-7s %-12s %-10s%n", "SYM", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (String symbol : PAIRS) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
            var strategy = new GoldTurtlePyramidStrategy("GoldTurtlePyramid", symbol,
                55, 20, 20, 0.5, 1, QTY);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtlePyramid", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-8s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                symbol, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Sweep maxUnits 1-4 × step 0.25/0.5/1.0 (12 cellules, XAU_USD). */
    private static void runSweep(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) { System.out.println("PAS DE DONNÉES"); return; }

        int[] units = {1, 2, 3, 4};
        double[] steps = {0.25, 0.5, 1.0};

        System.out.println("=== SWEEP XAU_USD (maxUnits × step) ===");
        System.out.printf("%-10s %-6s %-6s %-6s %-7s %-12s%n", "UNITS", "STEP", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int u : units) {
            for (double s : steps) {
                var strategy = new GoldTurtlePyramidStrategy("GoldTurtlePyramid", symbol,
                    55, 20, 20, s, u, QTY);
                BacktestResult r = RunContext.forStrategy(null, "GoldTurtlePyramid", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-10d %-6.2f %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    u, s, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward : IS 2006-2015 / OOS 2016-2025, XAU_USD. */
    private static void runWalkForward(BacktestExecutionCost cost, int maxUnits) throws Exception {
        String symbol = "XAU_USD";
        var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
        var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2025");
        List<Bar> isBars = isLoaded.bars();
        List<Bar> oosBars = oosLoaded.bars();

        System.out.println("=== WALK-FORWARD XAU_USD PYRAMID " + maxUnits + "u/0.5 (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-10s%n", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (var phase : new String[][]{{"IS", "2006-2015", "0"}, {"OOS", "2016-2025", "1"}}) {
            List<Bar> bars = phase[2].equals("0") ? isBars : oosBars;
            var strategy = new GoldTurtlePyramidStrategy("GoldTurtlePyramid", symbol,
                55, 20, 20, 0.5, maxUnits, QTY);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtlePyramid", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Régime de marché : bull 2006-2012 / bear 2013-2015 / bull2 2016-2025. */
    private static void runRegime(BacktestExecutionCost cost, int maxUnits) throws Exception {
        String symbol = "XAU_USD";
        String[] regimes = {"2006-2012", "2013-2015", "2016-2025"};

        System.out.println("=== RÉGIME XAU_USD PYRAMID " + maxUnits + "u/0.5 (bull/bear/bull2) ===");
        System.out.printf("%-10s %-6s %-6s %-6s %-7s %-12s %-10s%n", "REGIME", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (String spec : regimes) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, spec);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(spec + " : PAS DE DONNÉES"); continue; }
            var strategy = new GoldTurtlePyramidStrategy("GoldTurtlePyramid", symbol,
                55, 20, 20, 0.5, maxUnits, QTY);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtlePyramid", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                spec, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Lit maxUnits depuis --wf 2 / --regime 2 (défaut 4). */
    private static int unitsFromArgs(String[] args) {
        if (args.length > 1) {
            try { return Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        return 4;
    }
}
