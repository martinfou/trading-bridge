package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleD1Strategy;

import java.util.List;

/**
 * RunGoldTurtleD1 — Backtest GoldTurtleD1Strategy (Turtle Donchian 55/20 sur
 * barres D1 agrégées, XAU/USD) AVEC coûts (commission $0.07 + slippage 0.01%)
 * — jamais sans coûts. Variation D1-native de GoldTurtleTrend (mardi 25 août).
 *
 * Mode 1 (par défaut) : XAU_USD + contrôle EUR_USD (même mécanique, FX) pour
 *   isoler l'edge "instrument" de l'edge "mécanique".
 * Mode 2 (--sweep) : sweep paramétrique entrée 40/55/70 × sortie 15/20/25 sur
 *   XAU_USD pour vérifier plateau vs pic (anti-curve-fitting).
 * Mode 3 (--wf) : walk-forward IS 2006-2015 / OOS 2016-2025.
 * Mode 4 (--regime) : bull 2006-12 / bear 2013-15 / bull2 2016-25.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleD1
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleD1 --sweep
 */
public class RunGoldTurtleD1 {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String[] PAIRS = {"XAU_USD", "EUR_USD"};

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--sweep")) {
            runSweep(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--wf")) {
            runWalkForward(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--regime")) {
            runRegime(cost);
            return;
        }

        System.out.println("==================================================");
        System.out.println("GOLD TURTLE D1 — Donchian 55/20 (D1), XAU/USD");
        System.out.println("Coûts: commission $0.07 + slippage 0.01%");
        System.out.println("Capital: $" + CAPITAL);
        System.out.println("==================================================");

        for (String symbol : PAIRS) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
            var strategy = new GoldTurtleD1Strategy("GoldTurtleD1", symbol);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtleD1", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-8s bars=%-6d PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d "
                    + "net=$%10.2f ret=%-7.2f%% swap=$%8.2f comm=$%8.2f%n",
                symbol, bars.size(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap(), r.totalCommission());
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward : IS 2006-2015 (70%) / OOS 2016-2025 (30%), XAU_USD. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
        var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2025");
        List<Bar> isBars = isLoaded.bars();
        List<Bar> oosBars = oosLoaded.bars();

        System.out.println("=== WALK-FORWARD XAU_USD D1 (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (var phase : new String[][]{{"IS", "2006-2015", "0"}, {"OOS", "2016-2025", "1"}}) {
            List<Bar> bars = phase[2].equals("0") ? isBars : oosBars;
            var strategy = new GoldTurtleD1Strategy("GoldTurtleD1", symbol);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtleD1", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Régime de marché : bull (2006-2012), bear (2013-2015), bull2 (2016-2025). */
    private static void runRegime(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        String[] regimes = {"2006-2012", "2013-2015", "2016-2025"};

        System.out.println("=== RÉGIME DE MARCHÉ XAU_USD D1 (bull/bear/bull2) ===");
        System.out.printf("%-10s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "REGIME", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (String spec : regimes) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, spec);
            List<Bar> bars = loaded.bars();
            var strategy = new GoldTurtleD1Strategy("GoldTurtleD1", symbol);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtleD1", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                spec, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Sweep paramétrique : entrée 40/55/70 × sortie 15/20/25 (9 cellules, XAU_USD). */
    private static void runSweep(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
        List<Bar> bars = loaded.bars();

        int[] entries = {40, 55, 70};
        int[] exits = {15, 20, 25};

        System.out.println("=== SWEEP PARAMÉTRIQUE XAU_USD D1 (entrée × sortie) ===");
        System.out.printf("%-8s %-8s %-6s %-6s %-6s %-7s %-12s%n",
            "ENTRY", "EXIT", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int e : entries) {
            for (int x : exits) {
                var strategy = new GoldTurtleD1Strategy("GoldTurtleD1", symbol, e, x);
                BacktestResult r = RunContext.forStrategy(null, "GoldTurtleD1", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-8d %-8d %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    e, x, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }
}
