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
 * RunDateWindowSeasonal — Lundi 24 août 2026 (idée nouvelle) : pré-validation +
 * backtest des fenêtres saisonnières d'automne H2 avant qu'elles ne deviennent
 * tradables (USD/JPY Sep 27-Nov 11, USD/CAD Oct 12-Nov 26).
 *
 * Pré-validation (AutumnWindowCheck, méthode du 5 août) :
 *   - USD/JPY Sep27-Nov11 BUY : ❌ REJECT — avg IS -0.26% (edge ABSENT en IS),
 *     OOS +1.75% = régime yen faible post-2016, pas saisonnalité. Aussi le
 *     contrôle Oct (71% OOS) ≈ fenêtre (78%) — pas distinct.
 *   - USD/CAD Oct12-Nov26 BUY : ⚠️ MARGINAL — stable (IS 90% → OOS 78%) mais
 *     le contrôle Nov 1-30 est PLUS fort (OOS 86%) → la fenêtre officielle
 *     n'est pas le meilleur segment ; novembre seul est le vrai cluster.
 *
 * Ce runner backteste AVEC coûts :
 *   A) USD/CAD Oct12-Nov26 BUY  — fenêtre officielle SeasonalityFilter (marginal)
 *   B) USD/CAD Nov1-Nov30 BUY   — variante « novembre seul » (contrôle supérieur)
 *   C) USD/JPY Sep27-Nov11 BUY  — confirmation backtest du REJECT pré-validation
 *
 * Mode 2 (--wf) : walk-forward GBP? non — USD_CAD configs A/B IS 2006-2015 / OOS 2016-2026.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunDateWindowSeasonal
 *   java -cp "$CP" com.martinfou.trading.examples.RunDateWindowSeasonal --wf
 */
public class RunDateWindowSeasonal {

    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) {
            runWalkForward(cost);
            return;
        }
        if (args.length > 0 && args[0].equals("--sweep")) {
            runSweep(cost);
            return;
        }

        System.out.println("==================================================");
        System.out.println("DATE-WINDOW SEASONAL — fenêtres automne H2, coûts inclus");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("==================================================");

        // Configs : {label, symbol, startM, startD, endM, endD, side}
        String[][] configs = {
            {"A: USDCAD Oct12-Nov26 BUY (officielle)", "USD_CAD", "10", "12", "11", "26", "BUY"},
            {"B: USDCAD Nov1-Nov30 BUY (variante)",    "USD_CAD", "11", "1",  "11", "30", "BUY"},
            {"C: USDJPY Sep27-Nov11 BUY (REJECT conf)", "USD_JPY", "9", "27", "11", "11", "BUY"},
            {"D: USDCAD Oct12-Nov26 BUY — GBP contrôle", "GBP_USD", "10", "12", "11", "26", "BUY"},
            {"E: USDCAD Nov1-Nov30 BUY — GBP contrôle", "GBP_USD", "11", "1",  "11", "30", "BUY"},
        };

        System.out.printf("%-42s %-8s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "CONFIG", "SYMBOL", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        for (String[] cfg : configs) {
            String label = cfg[0], symbol = cfg[1];
            int sm = Integer.parseInt(cfg[2]), sd = Integer.parseInt(cfg[3]);
            int em = Integer.parseInt(cfg[4]), ed = Integer.parseInt(cfg[5]);
            Order.Side side = cfg[6].equals("BUY") ? Order.Side.BUY : Order.Side.SELL;

            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(label + " : PAS DE DONNÉES"); continue; }
            var strategy = new DateWindowSeasonalStrategy("DateWindowSeasonal", symbol,
                sm, sd, em, ed, side);
            BacktestResult r = RunContext.forStrategy(null, "DateWindowSeasonal", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-42s %-8s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
                label, symbol, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward IS 2006-2015 / OOS 2016-2026 pour les 2 configs USD/CAD. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String symbol = "USD_CAD";
        int[][] windows = {{10, 12, 11, 26}, {11, 1, 11, 30}};
        String[] labels = {"Oct12-Nov26 (officielle)", "Nov1-Nov30 (variante)"};

        var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
        var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2026");
        List<Bar> isBars = isLoaded.bars();
        List<Bar> oosBars = oosLoaded.bars();

        System.out.println("=== WALK-FORWARD USD_CAD (IS 2006-2015 / OOS 2016-2026) ===");
        for (int w = 0; w < windows.length; w++) {
            System.out.println("\n--- " + labels[w] + " ---");
            System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
                "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
            for (var phase : new String[][]{{"IS", "0"}, {"OOS", "1"}}) {
                List<Bar> bars = phase[1].equals("0") ? isBars : oosBars;
                var strategy = new DateWindowSeasonalStrategy("DateWindowSeasonal", symbol,
                    windows[w][0], windows[w][1], windows[w][2], windows[w][3], Order.Side.BUY);
                BacktestResult r = RunContext.forStrategy(null, "DateWindowSeasonal", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
                    phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
            }
        }
        System.out.println("\nDONE");
    }

    /** Sweep robustesse : bordures fenêtre ±5 jours autour de Oct12→Nov26 (USD_CAD). */
    private static void runSweep(BacktestExecutionCost cost) throws Exception {
        String symbol = "USD_CAD";
        int baseSM = 10, baseSD = 12, baseEM = 11, baseED = 26;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); return; }

        System.out.println("=== SWEEP BORDURES fenêtre USD_CAD (base Oct12 → Nov26) ===");
        System.out.printf("%-22s %-6s %-6s %-6s %-7s %-12s %-9s%n",
            "WINDOW", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        int[] startOffsets = {-5, -3, 0, +3, +5};
        int[] endOffsets = {-5, -3, 0, +3, +5};
        for (int so : startOffsets) {
            for (int eo : endOffsets) {
                // Décalage simple : ±jours sur les jours de mois (bordures proches)
                int sm = baseSM, sd = baseSD + so, em = baseEM, ed = baseED + eo;
                // Gérer débordements de mois pour les offsets (simple : clamp au mois)
                if (sd < 1) { sd = 1; } if (sd > 31) { sd = 31; }
                if (ed < 1) { ed = 1; } if (ed > 30) { ed = 30; }
                var strategy = new DateWindowSeasonalStrategy("DateWindowSeasonal", symbol,
                    sm, sd, em, ed, Order.Side.BUY);
                BacktestResult r = RunContext.forStrategy(null, "DateWindowSeasonal", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("Oct%02d-%02d → Nov%02d-%02d  %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f%n",
                    sm, sd, em, ed, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }
}
