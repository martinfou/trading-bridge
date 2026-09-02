package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.DateWindowSeasonalStrategy;

import java.util.List;

/**
 * RunGoldJanuarySeasonal — Mercredi 2 septembre 2026 (pattern saisonnier) :
 * cluster hivernal XAU/USD (Janvier) ressorti du scan mensuel + vérifié par
 * GoldWinterCheck (Pattern D, IS/OOS + régimes).
 *
 * Pré-validation (GoldWinterCheck, XAU_USD 2006-2025) :
 *   - Jan 1-31 BUY : ALL +3.63% hit 70% | IS +4.37% hit 70% | OOS +2.90% hit 70%
 *     | bull06-12 +4.68% | BEAR13-15 +3.64% | bull2 16-25 +2.90% → positif dans
 *     TOUS les régimes, y compris le bear qui a tué XAU August (IS 70→OOS 50).
 *   - Contrôles voisins : Jul +1.45% (bear -0.96 négatif), Aug +1.66% (IS 70→OOS
 *     50, artefact connu), Oct +0.88% faible, Feb +1.12% (OOS 40%).
 *   - Dec 15-Jan 15 : OOS 100% (n=10, +3.69%) — cluster plus large.
 *
 * Backtest AVEC coûts ($0.07 + 0.01%, $50K, quantité fixe 10 oz — la famille or) :
 *   A) XAU/USD Jan 1-31 BUY      — fenêtre mensuelle naturelle
 *   B) XAU/USD Dec15-Jan15 BUY   — cluster hivernal court
 *   C) XAU/USD Dec1-Jan31 BUY    — cluster hivernal large
 *   D) XAU/USD Feb 1-28 BUY      — contrôle mois voisin
 *   E) XAU/USD Nov 1-30 BUY      — contrôle mois voisin
 *   F) EUR_USD Jan 1-31 BUY      — contrôle paire (January effect FX ?)
 *   G) GBP_USD Jan 1-31 BUY      — contrôle paire
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldJanuarySeasonal
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldJanuarySeasonal --wf
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldJanuarySeasonal --sweep
 */
public class RunGoldJanuarySeasonal {

    static final double CAPITAL = 50_000;
    static final double GOLD_QTY = 10;     // 10 oz — standard famille or
    static final double FX_QTY = 10_000;   // 10K units — standard fenêtres FX

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost); return; }
        if (args.length > 0 && args[0].equals("--sweep")) { runSweep(cost); return; }

        System.out.println("==================================================");
        System.out.println("GOLD JANUARY SEASONAL — cluster hivernal XAU, coûts inclus");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("==================================================");

        // Configs : {label, symbol, range, startM, startD, endM, endD, side, qty}
        String[][] configs = {
            {"A: XAU Jan1-31 BUY (candidate)", "XAU_USD", "2006-2025", "1", "1", "1", "31", "BUY", "10"},
            {"B: XAU Dec15-Jan15 BUY",         "XAU_USD", "2006-2025", "12", "15", "1", "15", "BUY", "10"},
            {"C: XAU Dec1-Jan31 BUY",          "XAU_USD", "2006-2025", "12", "1", "1", "31", "BUY", "10"},
            {"D: XAU Feb1-28 BUY (contrôle)",  "XAU_USD", "2006-2025", "2", "1", "2", "28", "BUY", "10"},
            {"E: XAU Nov1-30 BUY (contrôle)",  "XAU_USD", "2006-2025", "11", "1", "11", "30", "BUY", "10"},
            {"F: EUR Jan1-31 BUY (contrôle)",  "EUR_USD", "2006-2026", "1", "1", "1", "31", "BUY", "10000"},
            {"G: GBP Jan1-31 BUY (contrôle)",  "GBP_USD", "2006-2026", "1", "1", "1", "31", "BUY", "10000"},
        };

        System.out.printf("%-32s %-8s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "CONFIG", "SYMBOL", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        for (String[] cfg : configs) {
            String label = cfg[0], symbol = cfg[1], range = cfg[2];
            int sm = Integer.parseInt(cfg[3]), sd = Integer.parseInt(cfg[4]);
            int em = Integer.parseInt(cfg[5]), ed = Integer.parseInt(cfg[6]);
            Order.Side side = cfg[7].equals("BUY") ? Order.Side.BUY : Order.Side.SELL;
            double qty = Double.parseDouble(cfg[8]);

            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, range);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(label + " : PAS DE DONNÉES"); continue; }
            var strategy = new DateWindowSeasonalStrategy("GoldJanuarySeasonal", symbol,
                sm, sd, em, ed, side, qty);
            BacktestResult r = RunContext.forStrategy(null, "GoldJanuarySeasonal", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-32s %-8s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
                label, symbol, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward XAU Jan1-31 : IS 2006-2015 / OOS 2016-2025. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
        var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2025");
        List<Bar> isBars = isLoaded.bars();
        List<Bar> oosBars = oosLoaded.bars();

        System.out.println("=== WALK-FORWARD XAU Jan1-31 BUY (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        for (var phase : new String[][]{{"IS", "0"}, {"OOS", "1"}}) {
            List<Bar> bars = phase[1].equals("0") ? isBars : oosBars;
            var strategy = new DateWindowSeasonalStrategy("GoldJanuarySeasonal", symbol,
                1, 1, 1, 31, Order.Side.BUY, GOLD_QTY);
            BacktestResult r = RunContext.forStrategy(null, "GoldJanuarySeasonal", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
                phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
        }
        System.out.println("\nDONE");
    }

    /** Sweep robustesse : bordures autour de Jan 1→Jan 31 (XAU). Plateau attendu. */
    private static void runSweep(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2025");
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); return; }

        // Départs : mid-dec → début janv ; fins : mi-janv → fin janv.
        int[][] starts = {{12, 10}, {12, 15}, {12, 20}, {12, 26}, {1, 1}, {1, 5}};
        int[][] ends = {{1, 10}, {1, 15}, {1, 20}, {1, 25}, {1, 31}};

        System.out.println("=== SWEEP fenêtre hivernale XAU (départ × fin) ===");
        System.out.printf("%-18s %-6s %-6s %-6s %-7s %-12s %-9s%n",
            "WINDOW", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (int[] st : starts) {
            for (int[] en : ends) {
                var strategy = new DateWindowSeasonalStrategy("GoldJanuarySeasonal", symbol,
                    st[0], st[1], en[0], en[1], Order.Side.BUY, GOLD_QTY);
                BacktestResult r = RunContext.forStrategy(null, "GoldJanuarySeasonal", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("M%02dD%02d → M%02dD%02d       %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f%n",
                    st[0], st[1], en[0], en[1], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }
}
