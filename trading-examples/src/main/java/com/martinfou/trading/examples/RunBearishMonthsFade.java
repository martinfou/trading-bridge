package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.BearishMonthsFadeStrategy;

import java.util.List;

/**
 * RunBearishMonthsFade — Backtest BearishMonthsFadeStrategy (SELL May+Aug+Nov
 * family on risk pairs) AVEC coûts (commission $0.07 + slippage 0.01%).
 *
 * Mode 1 (par défaut) : multi-paires (GBP, AUD, NZD, EUR) 2006-2026.
 * Mode 2 (--wf) : walk-forward GBP IS 2006-2015 / OOS 2016-2026.
 * Mode 3 (--price) : sépare PnL prix vs swap (totalSwap) sur GBP.
 * Mode 4 (--decompose) : décompose par fenêtre (May/Aug/Nov seuls).
 * Mode 5 (--wf-decompose) : décompose par fenêtre avec split IS/OOS.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunBearishMonthsFade
 *   java -cp "$CP" com.martinfou.trading.examples.RunBearishMonthsFade --wf
 *   java -cp "$CP" com.martinfou.trading.examples.RunBearishMonthsFade --price
 *   java -cp "$CP" com.martinfou.trading.examples.RunBearishMonthsFade --decompose
 *   java -cp "$CP" com.martinfou.trading.examples.RunBearishMonthsFade --wf-decompose
 */
public class RunBearishMonthsFade {

    static final double CAPITAL = 50_000;
    static final String[] PAIRS = {"GBP_USD", "AUD_USD", "NZD_USD", "EUR_USD"};

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) {
            runWalkForward(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--price")) {
            runPriceVsSwap(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--decompose")) {
            runDecompose(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--wf-decompose")) {
            runWfDecompose(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--mayaug")) {
            runMayAug(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--sweep")) {
            runSweep(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--regime")) {
            runRegime(cost);
            return;
        }

        System.out.println("==================================================");
        System.out.println("BEARISH MONTHS FADE — SELL May+Aug+Nov family, H1");
        System.out.println("Coûts: commission $0.07 + slippage 0.01%");
        System.out.println("Capital: $" + CAPITAL);
        System.out.println("==================================================");

        for (String symbol : PAIRS) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
            var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol);
            BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-8s bars=%-6d PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d "
                    + "net=$%10.2f ret=%-7.2f%% swap=$%8.2f comm=$%8.2f%n",
                symbol, bars.size(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap(), r.totalCommission());
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward : IS 2006-2015 (70%) / OOS 2016-2026 (30%). Paires: GBP, EUR. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String[] symbols = {"GBP_USD", "EUR_USD"};
        int[][] monthSets = {{5, 8, 11}, {5, 8}};
        String[] monthLabels = {"FAMILLE 5+8+11", "MAY+AUG"};
        for (int m = 0; m < monthSets.length; m++) {
            for (String symbol : symbols) {
                var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
                var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2026");
                List<Bar> isBars = isLoaded.bars();
                List<Bar> oosBars = oosLoaded.bars();

                System.out.println("=== WALK-FORWARD " + symbol + " " + monthLabels[m]
                    + " (IS 2006-2015 / OOS 2016-2026) ===");
                System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-10s%n",
                    "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
                for (var phase : new String[][]{{"IS", "2006-2015"}, {"OOS", "2016-2026"}}) {
                    List<Bar> bars = phase[1].equals("2006-2015") ? isBars : oosBars;
                    var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol, monthSets[m]);
                    BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
                        RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                    System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                        phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                        r.totalTrades(), r.totalPnl(), r.totalReturnPct());
                }
                System.out.println();
            }
        }
        System.out.println("DONE");
    }

    /** Sépare le PnL prix du swap (artefact taux constants 2024-26). */
    private static void runPriceVsSwap(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
        List<Bar> bars = loaded.bars();
        var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol);
        BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.println("=== GBP_USD — PnL prix vs swap (2006-2026, coûts inclus) ===");
        System.out.printf("PF=%.2f WR=%.1f%% DD=%.2f%% trades=%d%n",
            r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades());
        System.out.printf("Net total (swap+comm inclus): $%,.2f%n", r.totalPnl());
        System.out.printf("Swap total: $%,.2f  | Commission: $%,.2f%n", r.totalSwap(), r.totalCommission());
        double pricePnl = r.totalPnl() - r.totalSwap() - r.totalCommission();
        System.out.printf("PnL prix seul (≈ net - swap - comm): $%,.2f%n", pricePnl);
        System.out.println("\nDONE");
    }

    /** Décompose la famille par fenêtre (May seul, Aug seul, Nov seul) + IS/OOS. */
    private static void runDecompose(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        int[][] windows = {{5}, {8}, {11}, {5, 8, 11}};
        String[] labels = {"MAY seul", "AUG seul", "NOV seul", "FAMILLE 5+8+11"};

        System.out.println("=== DÉCOMPOSITION GBP_USD 2006-2026 (coûts inclus) ===");
        System.out.printf("%-16s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "WINDOW", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (int i = 0; i < windows.length; i++) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
            List<Bar> bars = loaded.bars();
            var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol, windows[i]);
            BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-16s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                labels[i], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Décompose la famille par fenêtre avec split IS/OOS (2006-2015 / 2016-2026). */
    private static void runWfDecompose(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        int[][] windows = {{5}, {8}, {11}, {5, 8, 11}};
        String[] labels = {"MAY seul", "AUG seul", "NOV seul", "FAMILLE 5+8+11"};

        System.out.println("=== DÉCOMPOSITION IS/OOS GBP_USD (IS 2006-2015 / OOS 2016-2026) ===");
        System.out.printf("%-16s %-10s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "WINDOW", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (int i = 0; i < windows.length; i++) {
            for (var phase : new String[][]{{"IS", "2006-2015"}, {"OOS", "2016-2026"}}) {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, phase[1]);
                List<Bar> bars = loaded.bars();
                var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol, windows[i]);
                BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-16s %-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                    labels[i], phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }

    /** Sous-ensemble survivant MAY+AUG — multi-paires + IS/OOS. */
    private static void runMayAug(BacktestExecutionCost cost) throws Exception {
        String[] symbols = {"GBP_USD", "EUR_USD", "AUD_USD", "NZD_USD", "USD_JPY", "USD_CHF"};
        int[] mayAug = {5, 8};

        System.out.println("=== MAY+AUG SEUL (fenêtres validées IS/OOS) — 2006-2026, coûts inclus ===");
        System.out.printf("%-8s %-6s %-6s %-6s %-7s %-12s %-10s %-10s%n",
            "SYMBOL", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        for (String symbol : symbols) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
            var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol, mayAug);
            BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-8s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f %10.2f%n",
                symbol, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
        }
        System.out.println("\nDONE");
    }

    /** Régime de marché : bull (2006-2012), bear (2013-2015), bull2 (2016-2026). */
    private static void runRegime(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        int[] mayAug = {5, 8};
        String[] regimes = {"2006-2012", "2013-2015", "2016-2026"};

        System.out.println("=== RÉGIME GBP_USD MAY+AUG (bull/bear/bull2) ===");
        System.out.printf("%-10s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "REGIME", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (String spec : regimes) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, spec);
            List<Bar> bars = loaded.bars();
            var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol, mayAug);
            BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                spec, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Sweep robustesse : décalage entrée/sortie ±5 jours (plateau vs pic). */
    private static void runSweep(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        int[] mayAug = {5, 8};
        int[] offsets = {0, 3, 5, 7};

        System.out.println("=== SWEEP ROBUSTESSE GBP_USD MAY+AUG (entrée 1+o jours / sortie fin-o jours) ===");
        System.out.printf("%-12s %-12s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "ENTRY_OFS", "EXIT_OFS", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (int e : offsets) {
            for (int x : offsets) {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
                List<Bar> bars = loaded.bars();
                var strategy = new BearishMonthsFadeStrategy("BearishMonthsFade", symbol, mayAug, e, x);
                BacktestResult r = RunContext.forStrategy(null, "BearishMonthsFade", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-12d %-12d %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                    e, x, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }
}
