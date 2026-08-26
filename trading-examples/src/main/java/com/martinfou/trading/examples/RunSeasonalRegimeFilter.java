package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.RegimeFilteredSeasonalStrategy;
import com.martinfou.trading.strategies.creative.RegimeFilteredSeasonalStrategy.FilterMode;

import java.util.List;

/**
 * RunSeasonalRegimeFilter — Mercredi 26 août 2026 (pattern saisonnier) :
 * filtre de régime D1 (prix vs SMA-N) sur le calendrier saisonnier 4+5+8
 * (BUY Apr + SELL May + SELL Aug).
 *
 * Piste ouverte du 21 août (« filtre régime D1 — sur 2 trades/an, un filtre
 * mensuel a plus de sens que sur H1 ») + source tradernewbie (Rapport
 * saisonnalité) : les effets saisonniers sont plus forts ALIGNÉS à la
 * tendance longue (BUY Apr au-dessus SMA-N, SELL May/Aug en-dessous).
 *
 * Contrôles anti-curve-fitting :
 *   - OPPOSITE (inverse du filtre) → doit être PIRE que ALIGNED
 *   - Sweep N ∈ {40,60,80,120,150,250} → plateau requis
 *   - Walk-forward IS 2006-2015 / OOS 2016-2026
 *   - Paire contrôle USD_JPY (calendrier NON validé là-bas)
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunSeasonalRegimeFilter
 *   java -cp "$CP" com.martinfou.trading.examples.RunSeasonalRegimeFilter --wf
 *   java -cp "$CP" com.martinfou.trading.examples.RunSeasonalRegimeFilter --sweep
 */
public class RunSeasonalRegimeFilter {

    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost); return; }
        if (args.length > 0 && args[0].equals("--sweep")) { runSweep(cost); return; }

        System.out.println("==================================================");
        System.out.println("SEASONAL CALENDAR 4+5+8 + FILTRE RÉGIME D1 (SMA-N)");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("Hypothèse: BUY Apr au-dessus SMA, SELL May/Aug en-dessous");
        System.out.println("==================================================");

        String[] symbols = {"GBP_USD", "EUR_USD", "USD_JPY"};
        for (String symbol : symbols) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }

            System.out.println("\n--- " + symbol + " (2006-2026) ---");
            System.out.printf("%-30s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
                "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
            runConfig(bars, symbol, "baseline (sans filtre)", 0, FilterMode.NONE, cost);
            runConfig(bars, symbol, "ALIGNED SMA-50", 50, FilterMode.ALIGNED, cost);
            runConfig(bars, symbol, "ALIGNED SMA-100", 100, FilterMode.ALIGNED, cost);
            runConfig(bars, symbol, "ALIGNED SMA-200", 200, FilterMode.ALIGNED, cost);
            runConfig(bars, symbol, "OPPOSITE SMA-100 (contrôle)", 100, FilterMode.OPPOSITE, cost);
        }
        System.out.println("\nDONE");
    }

    private static void runConfig(List<Bar> bars, String symbol, String label,
                                  int sma, FilterMode mode, BacktestExecutionCost cost) {
        var strategy = new RegimeFilteredSeasonalStrategy("RegimeFilteredSeasonal", symbol, sma, mode);
        BacktestResult r = RunContext.forStrategy(null, "RegimeFilteredSeasonal", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.printf("%-30s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
    }

    /** Walk-forward IS 2006-2015 / OOS 2016-2026 — GBP_USD + EUR_USD. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        System.out.println("=== WALK-FORWARD (IS 2006-2015 / OOS 2016-2026) ===");
        String[] symbols = {"GBP_USD", "EUR_USD"};
        int[] smas = {0, 50, 100, 200};
        for (String symbol : symbols) {
            var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
            var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2026");
            List<Bar> isBars = isLoaded.bars();
            List<Bar> oosBars = oosLoaded.bars();
            System.out.println("\n--- " + symbol + " ---");
            System.out.printf("%-22s %-6s %-6s %-7s %-12s | %-6s %-6s %-7s %-12s%n",
                "CONFIG", "IS PF", "IS WR%", "IS N", "IS NET$", "OOS PF", "OOS WR%", "OOS N", "OOS NET$");
            for (int sma : smas) {
                String label = sma == 0 ? "baseline" : "ALIGNED SMA-" + sma;
                FilterMode mode = sma == 0 ? FilterMode.NONE : FilterMode.ALIGNED;
                var isS = new RegimeFilteredSeasonalStrategy("RFS", symbol, sma, mode);
                var oosS = new RegimeFilteredSeasonalStrategy("RFS", symbol, sma, mode);
                BacktestResult isR = RunContext.forStrategy(null, "RFS", isS, symbol,
                    RunMode.BACKTEST, isBars, CAPITAL, null, cost).run();
                BacktestResult oosR = RunContext.forStrategy(null, "RFS", oosS, symbol,
                    RunMode.BACKTEST, oosBars, CAPITAL, null, cost).run();
                System.out.printf("%-22s %-6.2f %-6.1f %-7d %12.2f | %-6.2f %-6.1f %-7d %12.2f%n",
                    label, isR.profitFactor(), isR.winRatePct(), isR.totalTrades(), isR.totalPnl(),
                    oosR.profitFactor(), oosR.winRatePct(), oosR.totalTrades(), oosR.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Sweep robustesse paramétrique : GBP ALIGNED N ∈ {40,60,80,120,150,250}. */
    private static void runSweep(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
        List<Bar> bars = loaded.bars();
        System.out.println("=== SWEEP GBP_USD ALIGNED (SMA 40 → 250) — plateau requis ===");
        System.out.printf("%-16s %-6s %-6s %-6s %-7s %-12s %-9s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        int[] smas = {40, 50, 60, 80, 100, 120, 150, 200, 250};
        for (int sma : smas) {
            var strategy = new RegimeFilteredSeasonalStrategy("RFS", symbol, sma, FilterMode.ALIGNED);
            BacktestResult r = RunContext.forStrategy(null, "RFS", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-16s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f%n",
                "ALIGNED SMA-" + sma, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }
}
