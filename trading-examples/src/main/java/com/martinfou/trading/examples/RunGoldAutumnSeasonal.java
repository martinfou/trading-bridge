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
 * RunGoldAutumnSeasonal — Mercredi 9 septembre 2026 (pattern saisonnier, 31e
 * résultat) : confirmation backtest du REJECT « cluster saisonnier or d'automne ».
 *
 * GoldAutumnCheck (Pattern D) : Sep 1-30 DEAD (OOS -0.44% hit 30%, bear -4.19%),
 * Nov 1-30 = artefact bull 2006-12 (OOS -0.27% hit 50%, bear -4.70% — le contrôle
 * backtest du 2 sept PF 1.45/WR 50% venait du bull), clusters Oct-Nov bear négatif
 * partout. Seul Oct 1-31 garde un hit 60/60 stable MAIS avg IS +0.19% ≈ 0 et bear
 * -0.32% = signature late-bloomer bull2 (même signature que USD/JPY Sep27 REJECT).
 *
 * Ce runner backteste AVEC coûts ($0.07 + 0.01%, $50K, 10 oz) :
 *   A) XAU/USD Oct 1-31 BUY  — fenêtre mensuelle (seul « survivant » du scan)
 *   B) XAU/USD Sep 15-Oct 31 BUY — cluster early-autumn (contrôle)
 *   C) XAU/USD Oct 1-31 BUY  — contrôle paire EUR_USD (artefact mécanique ?)
 *
 * Mode --wf : XAU Oct 1-31 IS 2006-2015 / OOS 2016-2025.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldAutumnSeasonal
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldAutumnSeasonal --wf
 */
public class RunGoldAutumnSeasonal {

    static final double CAPITAL = 50_000;
    static final double GOLD_QTY = 10;     // 10 oz — standard famille or
    static final double FX_QTY = 10_000;   // 10K units — standard fenêtres FX

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost); return; }

        System.out.println("==================================================");
        System.out.println("GOLD AUTUMN SEASONAL — confirmation REJECT, coûts inclus");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("==================================================");

        String[][] configs = {
            {"A: XAU Oct1-31 BUY (candidate)", "XAU_USD", "2006-2025", "10", "1", "10", "31", "BUY", "10"},
            {"B: XAU Sep15-Oct31 BUY",         "XAU_USD", "2006-2025", "9", "15", "10", "31", "BUY", "10"},
            {"C: EUR Oct1-31 BUY (contrôle)",  "EUR_USD", "2006-2026", "10", "1", "10", "31", "BUY", "10000"},
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
            var strategy = new DateWindowSeasonalStrategy("GoldAutumnSeasonal", symbol,
                sm, sd, em, ed, side, qty);
            BacktestResult r = RunContext.forStrategy(null, "GoldAutumnSeasonal", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-32s %-8s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
                label, symbol, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward XAU Oct1-31 : IS 2006-2015 / OOS 2016-2025. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
        var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2025");
        List<Bar> isBars = isLoaded.bars();
        List<Bar> oosBars = oosLoaded.bars();

        System.out.println("=== WALK-FORWARD XAU Oct1-31 BUY (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        for (var phase : new String[][]{{"IS", "0"}, {"OOS", "1"}}) {
            List<Bar> bars = phase[1].equals("0") ? isBars : oosBars;
            var strategy = new DateWindowSeasonalStrategy("GoldAutumnSeasonal", symbol,
                10, 1, 10, 31, Order.Side.BUY, GOLD_QTY);
            BacktestResult r = RunContext.forStrategy(null, "GoldAutumnSeasonal", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
                phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
        }
        System.out.println("\nDONE");
    }
}
