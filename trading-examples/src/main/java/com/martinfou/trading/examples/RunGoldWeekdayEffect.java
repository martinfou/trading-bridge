package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.util.List;

/**
 * RunGoldWeekdayEffect — Backtest GoldWeekdayEffectStrategy (long or pendant
 * les sessions MERCREDI / VENDREDI) AVEC coûts ($0.07 + 0.01%).
 *
 * Idée nouvelle du 31 août (lundi, 24e résultat). Pré-validation statistique
 * (GoldWeekdayCheck) : mercredi +0.057% (stable IS/OOS) et vendredi +0.096%
 * (fort IS, effrité OOS) sur XAU_USD ; EUR/GBP négatifs le vendredi → effet
 * or-spécifique (safe-haven week-end).
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayEffect        (configs WED/FRI/WED+FRI)
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayEffect --wf   (walk-forward IS/OOS)
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayEffect --regime
 */
public class RunGoldWeekdayEffect {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost); return; }
        if (args.length > 0 && args[0].equals("--regime")) { runRegime(cost); return; }
        if (args.length > 0 && args[0].equals("--beta")) { runBetaControl(cost); return; }

        System.out.println("==================================================");
        System.out.println("GOLD WEEKDAY EFFECT — long or sessions MERCREDI/VENDREDI");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("==================================================");

        // Configs : [name, wed, fri]
        String[][] configs = {
            {"WED", "true", "false"},
            {"FRI", "false", "true"},
            {"WED+FRI", "true", "true"},
        };
        String[] symbols = {"XAU_USD", "EUR_USD"};

        for (String[] cfg : configs) {
            System.out.printf("%n--- Config %s (mercredi=%s vendredi=%s) ---%n", cfg[0], cfg[1], cfg[2]);
            for (String symbol : symbols) {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
                List<Bar> bars = loaded.bars();
                if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
                var strategy = new GoldWeekdayEffectStrategy("GoldWeekdayEffect", symbol,
                    Boolean.parseBoolean(cfg[1]), Boolean.parseBoolean(cfg[2]));
                BacktestResult r = RunContext.forStrategy(null, "GoldWeekdayEffect", strategy, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("  %-8s bars=%-6d PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d "
                        + "net=$%10.2f ret=%-7.2f%% swap=$%8.2f comm=$%8.2f%n",
                    symbol, bars.size(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap(), r.totalCommission());
            }
        }
        System.out.println("\nDONE");
    }

    /** Contrôle bêta : long or TOUS les jours (5 sessions/semaine) = proxy bêta pur. */
    private static void runBetaControl(BacktestExecutionCost cost) throws Exception {
        System.out.println("=== CONTRÔLE BÊTA XAU_USD — long or tous les jours (5 sessions) ===");
        // tous les jours = strategy avec tous les jours ouvrés comme cibles
        // → équivalent "long or 24/5" : le PF de ce contrôle est la BÊTA.
        // On compare au vendredi seul (FRI) : si FRI ≈ beta, l'effet jour est un artefact.
        String[][] configs = {
            {"ALLDAYS (bêta)", "true,true,true,true,true"},
            {"MON+TUE+WED+THU (anti-FRI)", "true,true,true,true,false"},
            {"FRI seul", "false,false,false,false,true"},
        };
        for (String[] cfg : configs) {
            var loaded = HistoricalDataLoader.loadFromArgs("XAU_USD", "XAU_USD", YEAR_SPEC);
            List<Bar> bars = loaded.bars();
            boolean[] days = parseDays(cfg[1]);
            var strategy = new GoldWeekdayEffectStrategy("GoldWeekdayBeta", "XAU_USD", days);
            BacktestResult r = RunContext.forStrategy(null, "GoldWeekdayBeta", strategy, "XAU_USD",
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("  %-24s PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d net=$%10.2f ret=%-7.2f%%%n",
                cfg[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    private static boolean[] parseDays(String spec) {
        boolean[] days = new boolean[5]; // Mon..Fri
        String[] parts = spec.split(",");
        for (int i = 0; i < 5; i++) days[i] = Boolean.parseBoolean(parts[i]);
        return days;
    }

    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        System.out.println("=== WALK-FORWARD XAU_USD — FRI seul (IS 2006-2015 / OOS 2016-2025) ===");
        String[] phases = {"IS 2006-2015", "OOS 2016-2025"};
        String[] specs = {"2006-2015", "2016-2025"};
        for (int i = 0; i < 2; i++) {
            var loaded = HistoricalDataLoader.loadFromArgs("XAU_USD", "XAU_USD", specs[i]);
            List<Bar> bars = loaded.bars();
            var strategy = new GoldWeekdayEffectStrategy("GoldWeekdayEffect", "XAU_USD", false, true);
            BacktestResult r = RunContext.forStrategy(null, "GoldWeekdayEffect", strategy, "XAU_USD",
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("  %-14s PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d net=$%10.2f ret=%-7.2f%%%n",
                phases[i], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    private static void runRegime(BacktestExecutionCost cost) throws Exception {
        System.out.println("=== RÉGIME XAU_USD — FRI seul vs ALLDAYS (proxy bêta) ===");
        String[] regimes = {"2006-2012", "2013-2015", "2016-2025"};
        String[][] configs = {
            {"FRI seul", "false,false,false,false,true"},
            {"ALLDAYS (bêta)", "true,true,true,true,true"},
        };
        for (String[] cfg : configs) {
            System.out.printf("%n--- %s ---%n", cfg[0]);
            System.out.printf("  %-10s %-6s %-6s %-6s %-7s %-12s %-10s%n",
                "REGIME", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
            for (String spec : regimes) {
                var loaded = HistoricalDataLoader.loadFromArgs("XAU_USD", "XAU_USD", spec);
                List<Bar> bars = loaded.bars();
                boolean[] days = parseDays(cfg[1]);
                var strategy = new GoldWeekdayEffectStrategy("GoldWeekdayBeta", "XAU_USD", days);
                BacktestResult r = RunContext.forStrategy(null, "GoldWeekdayBeta", strategy, "XAU_USD",
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("  %-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-10.2f%n",
                    spec, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalReturnPct());
            }
        }
        System.out.println("\nDONE");
    }
}
