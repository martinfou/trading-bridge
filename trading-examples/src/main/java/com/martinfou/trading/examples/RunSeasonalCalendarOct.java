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
 * RunSeasonalCalendarOct — Deep dive vendredi 21 août 2026 : 2e long leg
 * du calendrier saisonnier (BUY October) vs baseline 4+5+8.
 *
 * Pré-validation statistique (SeasonalityAnalyzer) :
 *   GBP Oct IS 2006-2015 +1.25% hit 77.8% p=0.027 → OOS 2016-2026 -0.69% hit 28.6% ❌
 *   EUR Oct IS 2006-2015 +0.88% hit 77.8%      → OOS 2016-2026 -0.88% hit 28.6% ❌
 *   Signature identique à NOV (le poison du 19 août) : fort en IS, inversé en OOS.
 * Ce runner confirme par backtest avec coûts : le calendrier 4+5+8+10 est-il
 * dégradé par octobre ? Octobre seul est-il un edge ?
 *
 * Configs testées :
 *   A) {4,5,8}     BUY/SELL/SELL   — baseline validée (GBP PF 2.83)
 *   B) {4,5,8,10}  BUY/SELL/SELL/BUY — candidat 2e long leg
 *   C) {10}        BUY seul        — octobre isolé
 *   D) {4,10}      BUY/BUY         — les 2 long legs seuls
 *
 * Mode 1 (défaut) : multi-paires (GBP, EUR, AUD, NZD) 2006-2026.
 * Mode 2 (--wf) : walk-forward GBP configs A/B 2006-2015 / 2016-2026.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunSeasonalCalendarOct
 *   java -cp "$CP" com.martinfou.trading.examples.RunSeasonalCalendarOct --wf
 */
public class RunSeasonalCalendarOct {

    static final double CAPITAL = 50_000;
    static final String[] PAIRS = {"GBP_USD", "EUR_USD", "AUD_USD", "NZD_USD"};

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) {
            runWalkForward(cost);
            return;
        }

        System.out.println("==================================================");
        System.out.println("SEASONAL CALENDAR + OCTOBER — 2e long leg ? H1");
        System.out.println("Coûts: commission $0.07 + slippage 0.01%");
        System.out.println("Capital: $" + CAPITAL);
        System.out.println("==================================================");

        // Configs : mois + directions
        int[][] months = {
            {4, 5, 8},        // A baseline
            {4, 5, 8, 10},    // B + octobre
            {10},             // C octobre seul
            {4, 10}           // D longs seuls
        };
        Order.Side[][] dirs = {
            {Order.Side.BUY, Order.Side.SELL, Order.Side.SELL},
            {Order.Side.BUY, Order.Side.SELL, Order.Side.SELL, Order.Side.BUY},
            {Order.Side.BUY},
            {Order.Side.BUY, Order.Side.BUY}
        };
        String[] labels = {"A: 4+5+8", "B: 4+5+8+10", "C: 10 seul", "D: 4+10"};

        for (int c = 0; c < months.length; c++) {
            System.out.println("\n=== CONFIG " + labels[c] + " — 2006-2026, coûts inclus ===");
            System.out.printf("%-8s %-6s %-6s %-6s %-7s %-12s %-10s %-10s%n",
                "SYMBOL", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
            for (String symbol : PAIRS) {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
                List<Bar> bars = loaded.bars();
                if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
                var strategy = new SeasonalCalendarStrategy(
                    "SeasonalCalendarOct", symbol, months[c], dirs[c]);
                BacktestResult r = RunContext.forStrategy(null, "SeasonalCalendarOct", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-8s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f %10.2f%n",
                    symbol, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
            }
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward IS 2006-2015 / OOS 2016-2026 pour configs A (baseline) et B (+oct). */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String symbol = "GBP_USD";
        int[][] months = {{4, 5, 8}, {4, 5, 8, 10}};
        Order.Side[][] dirs = {
            {Order.Side.BUY, Order.Side.SELL, Order.Side.SELL},
            {Order.Side.BUY, Order.Side.SELL, Order.Side.SELL, Order.Side.BUY}
        };
        String[] labels = {"4+5+8 (baseline)", "4+5+8+10 (+oct)"};

        var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
        var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2026");
        List<Bar> isBars = isLoaded.bars();
        List<Bar> oosBars = oosLoaded.bars();

        System.out.println("=== WALK-FORWARD GBP_USD (IS 2006-2015 / OOS 2016-2026) ===");
        for (int c = 0; c < months.length; c++) {
            System.out.println("\n--- CONFIG " + labels[c] + " ---");
            System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-10s%n",
                "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
            for (var phase : new String[][]{{"IS", "0"}, {"OOS", "1"}}) {
                List<Bar> bars = phase[1].equals("0") ? isBars : oosBars;
                var strategy = new SeasonalCalendarStrategy(
                    "SeasonalCalendarOct", symbol, months[c], dirs[c]);
                BacktestResult r = RunContext.forStrategy(null, "SeasonalCalendarOct", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                    phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }
}
