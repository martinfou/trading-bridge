package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.SwapCalculator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.DateWindowSeasonalStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RunUsdcadSwapRetest — lundi 28 septembre 2026 (action DUE de la revue du 26 sept).
 *
 * Re-test de la seule fenêtre datée EXPLORE : USD/CAD BUY Oct 12 → Nov 26
 * (SeasonalityFilter, plateau 25/25). Le backtest du 24 août 2026 affichait
 * « Swap = 0 (clé USDCAD vs USD_CAD) » : le swap n'a JAMAIS été appliqué à USD_CAD.
 * La cause racine est corrigée dans SwapCalculator.ratesFor (normalisation des
 * underscores) ; ce runner mesure le net sous CINQ hypothèses de swap :
 *
 *   ZERO               swap = 0            → doit reproduire la baseline du 24 août (calibration)
 *   AS CODED           -2.5 / +1.0 pips/lot/jour (table du repo, constantes 2024-2026)
 *   INVERSÉ            +2.5 / -1.0
 *   RÉEL diff          différentiel de taux RÉEL par année, sans spread broker
 *   RÉEL diff + 0.25   0.25 pip/lot/jour de demi-spread en plus
 *
 * Table RÉELLE (moyennes annuelles, taux directeur US − Canada) :
 *   source US  : FRED FEDFUNDS (moyenne mensuelle des taux effectifs quotidiens)
 *   source CAD : Banque du Canada Valet V39079 (cible du taux à un jour) pour 2009+,
 *                FRED/OCDE IRSTCB01CAM156N pour 2006-2008 (Valet commence en avril 2009)
 *   conversion : 1 % de différentiel annuel = 1 000 $/an sur 100k unités = 2.74 $/jour
 *                = 0.274 pip/lot/jour (pip standard = 10 $)
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunUsdcadSwapRetest --all
 *   java -cp "$CP" com.martinfou.trading.examples.RunUsdcadSwapRetest --sweep
 *   java -cp "$CP" com.martinfou.trading.examples.RunUsdcadSwapRetest --wf
 */
public class RunUsdcadSwapRetest {

    static final double CAPITAL = 50_000;
    static final String SYMBOL = "USD_CAD";
    // Fenêtre officielle SeasonalityFilter
    static final int SM = 10, SD = 12, EM = 11, ED = 26;
    // Taux codés dans SwapCalculator.SWAP_RATES pour USDCAD
    static final double CODED_LONG = -2.5, CODED_SHORT = 1.0;

    /** Différentiel de taux directeur US − Canada (moyennes annuelles en %), 2006 → 2026. */
    static final double[] DIFF = {
        +0.652, // 2006  (OCDE)
        +0.415, // 2007  (OCDE)
        -1.281, // 2008  (OCDE)
        -0.090, // 2009  (Valet)
        -0.429, // 2010
        -0.898, // 2011
        -0.860, // 2012
        -0.892, // 2013
        -0.911, // 2014
        -0.492, // 2015
        -0.105, // 2016
        +0.293, // 2017
        +0.394, // 2018
        +0.408, // 2019
        -0.124, // 2020
        -0.170, // 2021
        -0.358, // 2022
        +0.253, // 2023
        +0.664, // 2024
        +1.567, // 2025
        +1.385, // 2026
    };

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--all";

        System.out.println("==============================================================");
        System.out.println("USDCAD Oct12-Nov26 BUY — RE-TEST AVEC SWAP RÉELLEMENT APPLIQUÉ");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("SwapCalculator.hasRates(USD_CAD) = " + SwapCalculator.hasRates(SYMBOL)
            + " | rates as coded: " + SwapCalculator.getLongSwap(SYMBOL) + " / "
            + SwapCalculator.getShortSwap(SYMBOL) + " pips/lot/jour");
        System.out.println("==============================================================");

        switch (mode) {
            case "--sweep" -> runSweep();
            case "--wf" -> runWalkForward();
            default -> runAll();
        }
        System.out.println("\nDONE");
    }

    // ------------------------------------------------------------------ hypothèses

    /** Table de taux par année : pipRate = 0.274 × différentiel, ± demi-spread. */
    static Map<Integer, double[]> realTable(double halfSpreadPips) {
        Map<Integer, double[]> m = new HashMap<>();
        for (int i = 0; i < DIFF.length; i++) {
            double pip = 0.274 * DIFF[i];
            m.put(2006 + i, new double[]{ pip - halfSpreadPips, -pip - halfSpreadPips });
        }
        m.put(-1, m.get(2026));   // repli pour toute année non listée
        return m;
    }

    record Hyp(String label, Double lo, Double sh, Map<Integer, double[]> table) {}

    static final Hyp ZERO = new Hyp("ZERO (baseline 24 août)", 0.0, 0.0, null);
    static final Hyp CODED = new Hyp("AS CODED (-2.5/+1.0)", CODED_LONG, CODED_SHORT, null);
    static final Hyp FLIP = new Hyp("INVERSÉ (+2.5/-1.0)", -CODED_LONG, -CODED_SHORT, null);
    static final Hyp REAL = new Hyp("RÉEL diff (0 spread)", null, null, realTable(0.0));
    static final Hyp REAL_SP = new Hyp("RÉEL diff + 0.25", null, null, realTable(0.25));

    static void apply(Hyp h) {
        if (h.table() != null) SwapCalculator.setYearlyRateOverride(SYMBOL, h.table());
        else SwapCalculator.setRateOverride(SYMBOL, h.lo(), h.sh());
    }

    // ------------------------------------------------------------------ modes

    private static void runAll() throws Exception {
        List<Bar> bars = load(SYMBOL, "2006-2026");

        header("1) FENÊTRE OFFICIELLE — 5 HYPOTHÈSES DE SWAP");
        for (Hyp h : new Hyp[]{ZERO, CODED, FLIP, REAL, REAL_SP}) {
            apply(h);
            print(h.label(), backtest(bars, SM, SD, EM, ED));
        }
        SwapCalculator.clearRateOverride();

        header("2) CONTRÔLE D'INSTRUMENT (GBP_USD, même fenêtre, table GBP_USD du repo)");
        List<Bar> gbp = load("GBP_USD", "2006-2026");
        for (Hyp h : new Hyp[]{
                new Hyp("ZERO", 0.0, 0.0, null),
                new Hyp("TABLE repo (GBP long -1.8)", null, null, null)}) {
            if (h.table() == null && h.lo() == null) {
                SwapCalculator.clearRateOverride();          // taux GBP_USD de la table
            } else {
                SwapCalculator.setRateOverride("GBP_USD", h.lo(), h.sh());
            }
            BacktestResult r = RunContext.forStrategy(null, "DateWindowSeasonal",
                new DateWindowSeasonalStrategy("DateWindowSeasonal", "GBP_USD", SM, SD, EM, ED, Order.Side.BUY),
                "GBP_USD", RunMode.BACKTEST, gbp, CAPITAL, null, cost()).run();
            print("GBP_USD " + h.label(), r);
        }
        SwapCalculator.clearRateOverride();

        header("3) VARIANTE Nov1-Nov30 (contrôle interne de la fenêtre)");
        for (Hyp h : new Hyp[]{ZERO, CODED, REAL}) {
            apply(h);
            print("Nov1-Nov30 " + h.label(), backtest(bars, 11, 1, 11, 30));
        }
        SwapCalculator.clearRateOverride();

        // --- Analyse du seuil de rupture -------------------------------------
        System.out.println("\n4) SEUIL DE RUPTURE DU SWAP (net = 0)");
        apply(CODED);
        BacktestResult r = backtest(bars, SM, SD, EM, ED);
        // swap$ = -2.5 pips × pipValue(1 $/pip) × totalRolloverDays ⇒ on isole les jours
        double totalRolloverDays = Math.abs(r.totalSwap() / CODED_LONG);
        double priceNet = r.totalPnl() - r.totalSwap();
        double breakEvenPipsPerDay = priceNet / totalRolloverDays;
        double avgDebit = 0.274 * avgAbsDiff();
        System.out.printf("Prix (hors swap) = +$%.2f | rollover-jours = %.0f (%.1f/trade)%n",
            priceNet, totalRolloverDays, totalRolloverDays / Math.max(1, r.totalTrades()));
        System.out.printf("Seuil de rupture = %.2f pip/lot/jour de DÉBIT (au-delà : net négatif)%n",
            breakEvenPipsPerDay);
        System.out.printf("Réel 2006-2025 : débit moyen = %.2f pip/jour ⇒ marge %.1f×%n",
            avgDebit, breakEvenPipsPerDay / avgDebit);
        SwapCalculator.clearRateOverride();
    }

    private static void runSweep() throws Exception {
        List<Bar> bars = load(SYMBOL, "2006-2026");
        for (Hyp h : new Hyp[]{REAL, CODED}) {
            apply(h);
            header("SWEEP BORDURES ±5j — swap " + h.label());
            System.out.printf("%-14s %-6s %-6s %-6s %-7s %-11s %-11s%n",
                "WINDOW", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$");
            int[] offs = {-5, -3, 0, 3, 5};
            int positive = 0, cells = 0, minTrades = Integer.MAX_VALUE;
            double worstNet = Double.MAX_VALUE;
            for (int so : offs) {
                for (int eo : offs) {
                    int sd = Math.max(1, Math.min(31, SD + so));
                    int ed = Math.max(1, Math.min(30, ED + eo));
                    BacktestResult r = backtest(bars, SM, sd, EM, ed);
                    cells++;
                    if (r.totalPnl() > 0) positive++;
                    minTrades = Math.min(minTrades, r.totalTrades());
                    worstNet = Math.min(worstNet, r.totalPnl());
                    System.out.printf("Oct%02d→Nov%02d     %-6.2f %-6.1f %-6.2f %-7d %11.2f %11.2f%n",
                        sd, ed, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                        r.totalTrades(), r.totalPnl(), r.totalSwap());
                }
            }
            System.out.printf("→ %d/%d cellules à net positif | min trades %d | pire net $%.2f%n",
                positive, cells, minTrades, worstNet);
        }
        SwapCalculator.clearRateOverride();
    }

    private static void runWalkForward() throws Exception {
        List<Bar> is = load(SYMBOL, "2006-2015");
        List<Bar> oos = load(SYMBOL, "2016-2026");
        for (Hyp h : new Hyp[]{ZERO, CODED, REAL}) {
            apply(h);
            header("WF (IS 2006-2015 / OOS 2016-2026) — swap " + h.label());
            System.out.printf("%-6s %-6s %-6s %-6s %-7s %-11s %-10s %-11s%n",
                "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "PRIX$");
            for (var p : new String[][]{{"IS", "0"}, {"OOS", "1"}}) {
                BacktestResult r = backtest(p[1].equals("0") ? is : oos, SM, SD, EM, ED);
                System.out.printf("%-6s %-6.2f %-6.1f %-6.2f %-7d %11.2f %10.2f %11.2f%n",
                    p[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl(), r.totalSwap(), r.totalPnl() - r.totalSwap());
            }
        }
        SwapCalculator.clearRateOverride();
    }

    // ------------------------------------------------------------------ helpers

    /** Débit moyen |différentiel| sur les années réellement tradées (2006-2025). */
    private static double avgAbsDiff() {
        double s = 0;
        for (int i = 0; i < 20; i++) s += Math.abs(DIFF[i]);
        return s / 20;
    }

    private static BacktestExecutionCost cost() {
        return BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
    }

    private static void header(String title) {
        System.out.println("\n=== " + title + " ===");
        System.out.printf("%-26s %-6s %-6s %-6s %-7s %-11s %-10s %-11s%n",
            "SWAP MODEL", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "PRIX$");
    }

    private static List<Bar> load(String symbol, String years) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, years);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) throw new IllegalStateException("PAS DE DONNÉES pour " + symbol + " " + years);
        return bars;
    }

    private static BacktestResult backtest(List<Bar> bars, int sm, int sd, int em, int ed) throws Exception {
        var strategy = new DateWindowSeasonalStrategy("DateWindowSeasonal", SYMBOL, sm, sd, em, ed, Order.Side.BUY);
        return RunContext.forStrategy(null, "DateWindowSeasonal", strategy, SYMBOL,
            RunMode.BACKTEST, bars, CAPITAL, null, cost()).run();
    }

    private static void print(String label, BacktestResult r) {
        System.out.printf("%-26s %-6.2f %-6.1f %-6.2f %-7d %11.2f %10.2f %11.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalSwap(), r.totalPnl() - r.totalSwap());
    }
}
