package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtlePartialExitStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtlePyramidStrategy;

import java.util.List;

/**
 * RunGoldTurtlePartialExit — Deep dive vendredi 21 août 2026 : sortie partielle
 * S2 (½ au Donchian 10, ½ au Donchian 20) sur GoldTurtlePyramid.
 *
 * Piste ouverte du 18 août : maxUnits=4 double le net (+$39.1K) mais triple le DD
 * (29.3% > gate 20%). La sortie en 2 paliers devrait verrouiller du PnL avant les
 * retracements profonds → DD des configs 3-4u sous le gate, net préservé.
 *
 * Mode 1 (défaut) : comparaison XAU_USD maxUnits 2/3/4 — pyramide standard vs
 *   sortie partielle S2 (Donchian 10/20). + contrôle EUR_USD (même mécanique).
 * Mode 2 (--wf) : walk-forward XAU 2u et 4u S2 (IS 2006-2015 / OOS 2016-2025).
 * Mode 3 (--regime) : bull/bear/bull2 XAU 4u S2.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtlePartialExit [--wf|--regime]
 */
public class RunGoldTurtlePartialExit {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final double QTY = 10;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf"))    { runWalkForward(cost); return; }
        if (args.length > 0 && args[0].equals("--regime")){ runRegime(cost); return; }

        System.out.println("==================================================");
        System.out.println("GOLD TURTLE PARTIAL EXIT S2 — Donchian 55/10/20, XAU H1");
        System.out.println("Sortie: ½ au Donchian 10, ½ au Donchian 20 (pyramiding ½ ATR)");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $50K");
        System.out.println("==================================================");

        String[] symbols = {"XAU_USD", "EUR_USD"};
        int[] units = {2, 3, 4};

        for (int u : units) {
            System.out.println("\n=== maxUnits=" + u + " — 2006-2025, coûts inclus ===");
            System.out.printf("%-8s %-16s %-6s %-6s %-6s %-7s %-12s %-10s %-10s%n",
                "SYMBOL", "VARIANT", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
            for (String symbol : symbols) {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
                List<Bar> bars = loaded.bars();
                if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }

                // Variante standard (pyramide, sortie tout d'un coup)
                var std = new GoldTurtlePyramidStrategy("GoldTurtlePyramid", symbol,
                    55, 20, 20, 0.5, u, QTY);
                BacktestResult rStd = RunContext.forStrategy(null, "GoldTurtlePyramid", std, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-8s %-16s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f %10.2f%n",
                    symbol, "PYRAMIDE STD", rStd.profitFactor(), rStd.winRatePct(),
                    rStd.maxDrawdownPct(), rStd.totalTrades(), rStd.totalPnl(),
                    rStd.totalReturnPct(), rStd.totalSwap());

                // Variante sortie partielle S2 (Donchian 10/20)
                var s2 = new GoldTurtlePartialExitStrategy("GoldTurtlePartialExit", symbol,
                    55, 20, 10, 20, 0.5, u, QTY);
                BacktestResult rS2 = RunContext.forStrategy(null, "GoldTurtlePartialExit", s2, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-8s %-16s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f %10.2f%n",
                    symbol, "S2 PARTIEL 10/20", rS2.profitFactor(), rS2.winRatePct(),
                    rS2.maxDrawdownPct(), rS2.totalTrades(), rS2.totalPnl(),
                    rS2.totalReturnPct(), rS2.totalSwap());
            }
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward XAU 2u et 4u S2 : IS 2006-2015 / OOS 2016-2025. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        var isLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2015");
        var oosLoaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2016-2025");
        List<Bar> isBars = isLoaded.bars();
        List<Bar> oosBars = oosLoaded.bars();

        System.out.println("=== WALK-FORWARD XAU_USD S2 (IS 2006-2015 / OOS 2016-2025) ===");
        for (int u : new int[]{2, 4}) {
            System.out.println("\n--- maxUnits=" + u + " S2 ---");
            System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-10s%n",
                "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
            for (var phase : new String[][]{{"IS", "0"}, {"OOS", "1"}}) {
                List<Bar> bars = phase[1].equals("0") ? isBars : oosBars;
                var s2 = new GoldTurtlePartialExitStrategy("GoldTurtlePartialExit", symbol,
                    55, 20, 10, 20, 0.5, u, QTY);
                BacktestResult r = RunContext.forStrategy(null, "GoldTurtlePartialExit", s2, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                    phase[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }

    /** Régime : bull 2006-2012 / bear 2013-2015 / bull2 2016-2025 — XAU 4u S2. */
    private static void runRegime(BacktestExecutionCost cost) throws Exception {
        String symbol = "XAU_USD";
        String[] regimes = {"2006-2012", "2013-2015", "2016-2025"};

        System.out.println("=== RÉGIME XAU_USD 4u S2 (bull/bear/bull2) ===");
        System.out.printf("%-10s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "REGIME", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
        for (String spec : regimes) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, spec);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(spec + " : PAS DE DONNÉES"); continue; }
            var s2 = new GoldTurtlePartialExitStrategy("GoldTurtlePartialExit", symbol,
                55, 20, 10, 20, 0.5, 4, QTY);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtlePartialExit", s2, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                spec, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }
}
