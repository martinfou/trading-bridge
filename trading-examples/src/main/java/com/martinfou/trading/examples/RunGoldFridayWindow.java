package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldFridayWindowStrategy;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.util.List;

/**
 * RunGoldFridayWindow — Variation GoldWeekdayEffect FRI (mardi 8 sept 2026) :
 * fenêtres horaires de la session du vendredi (UTC) sur XAU/USD.
 *
 * Pré-validation GoldFridayHourCheck : le vendredi or full-day OOS = +0.037%
 * cumulé, concentré en ASIE/early (00:00-08:00) ; la jambe NY afternoon
 * (13:00-20:00) est IS-only (bull or 2006-15). HYPOTHÈSE : une fenêtre
 * restreinte garde l'edge OOS-stable sans la jambe beta IS-only.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldFridayWindow           (sweep fenêtres)
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldFridayWindow --wf      (walk-forward)
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldFridayWindow --regime  (régimes)
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldFridayWindow --eur     (contrôle EUR)
 */
public class RunGoldFridayWindow {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String GOLD = "XAU_USD";

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost); return; }
        if (args.length > 0 && args[0].equals("--regime")) { runRegime(cost); return; }
        if (args.length > 0 && args[0].equals("--eur")) { runEurControl(cost); return; }

        System.out.println("==================================================");
        System.out.println("GOLD FRIDAY WINDOW — XAU/USD H1, fenêtres UTC de la session vendredi");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("Réf. 31 août GoldWeekdayEffect FRI : PF 1.27 / +$15 131 / 1041 trades");
        System.out.println("Pré-validation 8 sept : bid OOS concentré 00:00-08:00, NY afternoon IS-only");
        System.out.println("==================================================");

        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        System.out.printf("XAU_USD: %d bars%n%n", xau.size());

        System.out.println("--- Référence GoldWeekdayEffect FRI (doit reproduire 1.27/+15131/1041) ---");
        runOne(cost, "GoldWeekdayEffect FRI", new GoldWeekdayEffectStrategy("GoldWeekdayEffect", GOLD, false, true), xau);

        System.out.println("\n--- Sweep fenêtres [entryHour, exitHour) UTC vendredi ---");
        System.out.printf("%-26s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "WINDOW", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        // Prefixes de session (entrée 00:00, sortie croissante)
        int[][] windows = {
            {0, 8},   // Asia/early
            {0, 12},  // Asia + Londres matin
            {0, 16},  // Asia + Londres + overlap
            {0, 21},  // full-day vendredi (~baseline GoldWeekdayEffect sans week-end)
            {8, 21},  // Londres + NY
            {12, 21}, // overlap + NY (fin de journée)
            {16, 21}, // NY late seul (dé-risk avant fermeture)
            {13, 21}, // NY afternoon seul
            {8, 16},  // Londres + overlap
            {12, 16}, // overlap seul
        };
        for (int[] w : windows) {
            String label = String.format("[%02d:%02d)", w[0], w[1]);
            runOne(cost, label, new GoldFridayWindowStrategy("GoldFridayWindow", GOLD, w[0], w[1]), xau);
        }
        System.out.println("\nDONE");
    }

    private static void runOne(BacktestExecutionCost cost, String label,
                               com.martinfou.trading.core.Strategy strategy, List<Bar> bars) {
        BacktestResult r = RunContext.forStrategy(null, strategy.name(), strategy, GOLD,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.printf("%-26s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
    }

    /** Walk-forward IS 2006-2015 / OOS 2016-2025 sur les fenêtres clés. */
    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        String[] specs = {"2006-2015", "2016-2025"};
        int[][] windows = {{0, 21}, {0, 8}, {0, 16}, {16, 21}, {13, 21}};
        System.out.println("=== WALK-FORWARD XAU_USD fenêtres vendredi (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-14s %-12s %-6s %-6s %-6s %-7s %-12s%n",
            "WINDOW", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int[] w : windows) {
            String label = String.format("[%02d:%02d)", w[0], w[1]);
            for (String spec : specs) {
                var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, spec).bars();
                var strat = new GoldFridayWindowStrategy("GoldFridayWindow", GOLD, w[0], w[1]);
                BacktestResult r = RunContext.forStrategy(null, "GoldFridayWindow", strat, GOLD,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-14s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    label, spec, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Régimes : bull 2006-12 / bear 2013-15 / bull2 2016-25. */
    private static void runRegime(BacktestExecutionCost cost) throws Exception {
        String[] regimes = {"bull 2006-12", "bear 2013-15", "bull2 2016-25"};
        String[] specs = {"2006-2012", "2013-2015", "2016-2025"};
        int[][] windows = {{0, 21}, {0, 8}, {16, 21}};
        System.out.println("=== RÉGIME XAU_USD fenêtres vendredi ===");
        System.out.printf("%-14s %-12s %-6s %-6s %-6s %-7s %-12s%n",
            "WINDOW", "REGIME", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int[] w : windows) {
            String label = String.format("[%02d:%02d)", w[0], w[1]);
            for (int i = 0; i < regimes.length; i++) {
                var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
                var strat = new GoldFridayWindowStrategy("GoldFridayWindow", GOLD, w[0], w[1]);
                BacktestResult r = RunContext.forStrategy(null, "GoldFridayWindow", strat, GOLD,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-14s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    label, regimes[i], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Contrôle EUR_USD : l'edge est-il instrument-spécifique (or) ou mécanique ? */
    private static void runEurControl(BacktestExecutionCost cost) throws Exception {
        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", YEAR_SPEC).bars();
        System.out.println("=== CONTRÔLE EUR_USD — mêmes fenêtres vendredi ===");
        System.out.printf("%-14s %-6s %-6s %-6s %-7s %-12s%n",
            "WINDOW", "PF", "WR%", "DD%", "TRADES", "NET$");
        int[][] windows = {{0, 21}, {0, 8}, {16, 21}, {13, 21}};
        for (int[] w : windows) {
            String label = String.format("[%02d:%02d)", w[0], w[1]);
            var strat = new GoldFridayWindowStrategy("GoldFridayWindow", "EUR_USD", w[0], w[1]);
            BacktestResult r = RunContext.forStrategy(null, "GoldFridayWindow", strat, "EUR_USD",
                RunMode.BACKTEST, eur, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl());
        }
        System.out.println("\nDONE");
    }
}
