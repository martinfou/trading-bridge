package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleDualGateStrategy;

import java.time.*;
import java.util.*;

/**
 * RunGoldTurtleSizeOverlay — Variation, mardi 15 septembre 2026 (36e résultat).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUESTION (piste restante de la revue hebdo du 12 sept, formulée le 14 sept)
 * ─────────────────────────────────────────────────────────────────────────────
 * Le gate à deux jambes (« USD ferme » × « stress actions ») a été testé comme
 * SÉLECTEUR d'entrées :
 *   - AND (quadrant twin, dérive forward la plus forte) → REJECT (PF 1.01, WF OOS 0.84) ;
 *   - OR (union des canaux refuge) → EXPLORE, meilleure config (PF 1.37, +$20 157, 839 trades).
 *
 * Mais la DÉRIVE du quadrant est validée (10 sept, EXPLORE). Piste jamais testée :
 * le même signal comme **MODULATEUR DE TAILLE** — ne rien jeter, tout trader, mais
 * DOUBLER la taille quand la condition refuge est vraie.
 *
 * La classe GoldTurtleDualGateStrategy est généralisée par (unitsAllowed, unitsBlocked) :
 *   - (1, 0) = GATE PUR     → comportement publié du 14 sept, reproduit au centime ;
 *   - (1, 2) = OVERLAY 2u   → tous les breakouts tradés, taille doublée dans l'état refuge ;
 *   - (1, 3) = OVERLAY 3u   → idem, taille triplée ;
 *   - (2, 2) = FLAT 2u      → contrôle de mise à l'échelle uniforme (Pattern C).
 *
 * Ce que la matrice départage :
 *   - overlay > gate en net ET en PF/DD → l'information de régime est une AMPLITUDE, pas
 *     un filtre : garder la traîne de PnL des trades « hors refuge » à taille réduite bat
 *     le fait de les jeter → nouveau mécanisme, config famille or potentiellement meilleure ;
 *   - overlay ≈ flat 2u (Pattern C) → l'état refuge ne porte aucune information de qualité,
 *     seul l'effet de levier existe → REJECT du concept ;
 *   - overlay ≈ baseline + gate_net → cohérence additive de la décomposition.
 *
 * ⚠️ VALIDATION D'IMPLÉMENTATION OBLIGATOIRE (avant toute conclusion) :
 *   - baseline OFF (1,0)           = 1.17 / 41.5% / 9.35% / 1594 / +$17 704.86  (17 août)
 *   - A_ONLY OPP SMA500 (1,0)      = 1.34 / 830 / +$18 143.09                    (28 août)
 *   - OR A=OPP B=ALI (1,0)         = 1.37 / 6.69% / 839 / +$20 157.21           (14 sept)
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleSizeOverlay
 *             [--validate | --overlay | --long | --sweep | --wf | --regime | --sig]
 */
public class RunGoldTurtleSizeOverlay {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String GOLD = "XAU_USD";
    static final int SMA_A = 500;     // DXY ~21 jours H1
    static final int SMA_B = 4800;    // S&P ~200 jours H1

    static final int ALIGNED  = GoldTurtleDualGateStrategy.POL_ALIGNED;
    static final int OPPOSITE = GoldTurtleDualGateStrategy.POL_OPPOSITE;
    static final int BOTH = GoldTurtleDualGateStrategy.SIDE_BOTH;
    static final int LONG_ONLY = GoldTurtleDualGateStrategy.SIDE_LONG_ONLY;
    static final int SHORT_ONLY = GoldTurtleDualGateStrategy.SIDE_SHORT_ONLY;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        Map<Long, Double> dxy = RunGoldTurtleDualGate.buildDxy(YEAR_SPEC);
        Map<Long, Double> spx = RunGoldTurtleDualGate.buildSpxOnXauGrid(xau);

        String mode = args.length > 0 ? args[0] : "--overlay";
        switch (mode) {
            case "--validate" -> runValidate(cost, xau, dxy, spx);
            case "--long"     -> runLong(cost, xau, dxy, spx);
            case "--sweep"    -> runSweep(cost, xau, dxy, spx);
            case "--wf"       -> runWalkForward(cost);
            case "--regime"   -> runRegime(cost);
            case "--sig"      -> runSignature(cost, xau, dxy, spx);
            default           -> runOverlay(cost, xau, dxy, spx);
        }
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- validation

    private static void runValidate(BacktestExecutionCost cost, List<Bar> xau,
                                    Map<Long, Double> dxy, Map<Long, Double> spx) {
        System.out.println("=== VALIDATION D'IMPLÉMENTATION (doit reproduire les références publiées) ===");
        header();
        row(cost, "Baseline OFF (1,0)   [=17 août]", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, BOTH, dxy, spx);
        row(cost, "A_ONLY OPP (1,0)     [=28 août]", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx);
        row(cost, "OR A=OPP B=ALI (1,0) [=14 sept]", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx);
        row(cost, "AND twin (1,0)      [=14 sept]", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx);
        System.out.println("\n-- contrôles de mise à l'échelle (Pattern C : amplificateur uniforme) --");
        row(cost, "Baseline FLAT 2u (2,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 2, 2, BOTH, dxy, spx);
        row(cost, "Baseline FLAT 3u (3,3)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 3, 3, BOTH, dxy, spx);
    }

    // ---------------------------------------------------------------- matrice overlay

    private static void runOverlay(BacktestExecutionCost cost, List<Bar> xau,
                                   Map<Long, Double> dxy, Map<Long, Double> spx) throws Exception {
        System.out.println("=====================================================================");
        System.out.println("GOLD TURTLE — SÉLECTEUR (gate) vs AMPLIFICATEUR (overlay de taille)");
        System.out.println("XAU/USD H1 2006-2025 | coûts $0.07 + 0.01% | capital $50K | 10 oz/unité");
        System.out.println("Jambe A = DXY synthétique vs SMA " + SMA_A + " | jambe B = S&P 500 vs SMA " + SMA_B);
        System.out.println("=====================================================================");

        System.out.println("\n--- BLOC 1 : profil de taille × config (BOTH) ---");
        System.out.println("(gate (1,0) = jette les trades hors condition | overlay (1,2) = garde tout, double l'état refuge)");
        header();
        row(cost, "Baseline OFF (1,0)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, BOTH, dxy, spx);

        Object[][] configs = {
            {"A_ONLY OPP (USD ferme)", GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED},
            {"B_ONLY ALI (stress SPX)", GoldTurtleDualGateStrategy.MODE_B_ONLY, ALIGNED, ALIGNED},
            {"AND A=OPP B=ALI (twin)", GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED},
            {"OR  A=OPP B=ALI (union refuge)", GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED},
        };
        for (Object[] c : configs) {
            String label = (String) c[0];
            int mode = (int) c[1], pa = (int) c[2], pb = (int) c[3];
            row(cost, label + " GATE (1,0)", GOLD, xau, mode, pa, pb, 1, 0, BOTH, dxy, spx);
            row(cost, label + " OVERLAY (1,2)", GOLD, xau, mode, pa, pb, 1, 2, BOTH, dxy, spx);
            row(cost, label + " OVERLAY (1,3)", GOLD, xau, mode, pa, pb, 1, 3, BOTH, dxy, spx);
            row(cost, label + " FLAT (2,2)", GOLD, xau, mode, pa, pb, 2, 2, BOTH, dxy, spx);
        }

        System.out.println("\n--- BLOC 2 : union élargie (OR A=OPP B=OPP) + variantes de profil ---");
        header();
        row(cost, "OR A=OPP B=OPP GATE (1,0)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, OPPOSITE, 1, 0, BOTH, dxy, spx);
        row(cost, "OR A=OPP B=OPP OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, OPPOSITE, 1, 2, BOTH, dxy, spx);
        row(cost, "AND A=OPP B=OPP OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, OPPOSITE, 1, 2, BOTH, dxy, spx);
        row(cost, "AND A=ALI B=OPP OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, BOTH, dxy, spx);
        row(cost, "AND A=ALI B=OPP OVERLAY (1,3)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 3, BOTH, dxy, spx);
        row(cost, "AND A=ALI B=OPP FLAT (2,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 2, 2, BOTH, dxy, spx);
        row(cost, "AND A=ALI B=OPP GATE (1,0) [ref]", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 0, BOTH, dxy, spx);
        row(cost, "A_ONLY ALI OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_A_ONLY, ALIGNED, ALIGNED, 1, 2, BOTH, dxy, spx);
        row(cost, "B_ONLY OPP OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_B_ONLY, ALIGNED, OPPOSITE, 1, 2, BOTH, dxy, spx);

        System.out.println("\n--- BLOC 3 : contrôle directionnel — les états NON-refuge (profil inversé) ---");
        System.out.println("(overlay (2,1) = double la taille HORS condition = anti-signal : doit être destructeur)");
        header();
        row(cost, "A_ONLY OPP OVERLAY-Inv (2,1)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 2, 1, BOTH, dxy, spx);
        row(cost, "A_ONLY ALI OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_A_ONLY, ALIGNED, ALIGNED, 1, 2, BOTH, dxy, spx);
        row(cost, "AND twin OVERLAY-Inv (2,1)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 2, 1, BOTH, dxy, spx);

        // --- contrôle instrument : EUR_USD ---
        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", YEAR_SPEC).bars();
        Map<Long, Double> spxEur = RunGoldTurtleDualGate.buildSpxOnXauGrid(eur);
        System.out.println("\n--- Contrôle EUR_USD (même mécanique, mêmes gauges — aucun edge Turtle) ---");
        header();
        row(cost, "EUR baseline (1,0)", "EUR_USD", eur,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, BOTH, dxy, spxEur);
        row(cost, "EUR OR A=OPP B=ALI GATE (1,0)", "EUR_USD", eur,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spxEur);
        row(cost, "EUR OR A=OPP B=ALI OVERLAY (1,2)", "EUR_USD", eur,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spxEur);
        row(cost, "EUR A_ONLY OPP OVERLAY (1,2)", "EUR_USD", eur,
            GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spxEur);
        row(cost, "EUR AND A=ALI B=OPP GATE (1,0)", "EUR_USD", eur,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 0, BOTH, dxy, spxEur);
        row(cost, "EUR AND A=ALI B=OPP OVERLAY (1,2)", "EUR_USD", eur,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, BOTH, dxy, spxEur);
        row(cost, "EUR AND A=ALI B=OPP OVERLAY (1,3)", "EUR_USD", eur,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 3, BOTH, dxy, spxEur);
    }

    // ---------------------------------------------------------------- piste long-only

    private static void runLong(BacktestExecutionCost cost, List<Bar> xau,
                                Map<Long, Double> dxy, Map<Long, Double> spx) {
        System.out.println("=== LONG-ONLY vs BOTH (signature LONG/SHORT du 11 sept : le short saigne) ===");
        System.out.println("Turtle Donchian 55/20 XAU H1 2006-2025, coûts complets");
        header();
        row(cost, "BOTH    Baseline (1,0)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, BOTH, dxy, spx);
        row(cost, "LONG    Baseline (1,0)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, LONG_ONLY, dxy, spx);
        row(cost, "SHORT   Baseline (1,0)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, SHORT_ONLY, dxy, spx);
        row(cost, "LONG    OR A=OPP B=ALI GATE (1,0)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 0, LONG_ONLY, dxy, spx);
        row(cost, "LONG    OR A=OPP B=ALI OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, LONG_ONLY, dxy, spx);
        row(cost, "LONG    OR A=OPP B=ALI OVERLAY (1,3)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 3, LONG_ONLY, dxy, spx);
        row(cost, "LONG    A_ONLY OPP OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 2, LONG_ONLY, dxy, spx);
        row(cost, "LONG    AND twin OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 1, 2, LONG_ONLY, dxy, spx);
        row(cost, "LONG    AND A=ALI B=OPP OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, LONG_ONLY, dxy, spx);
        row(cost, "LONG    AND A=ALI B=OPP OVERLAY (1,3)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 3, LONG_ONLY, dxy, spx);
        row(cost, "SHORT   AND A=ALI B=OPP OVERLAY (1,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, SHORT_ONLY, dxy, spx);
        row(cost, "LONG    Baseline OVERLAY-none FLAT (2,2)", GOLD, xau,
            GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 2, 2, LONG_ONLY, dxy, spx);
    }

    // ---------------------------------------------------------------- sweep 2D

    private static void runSweep(BacktestExecutionCost cost, List<Bar> xau,
                                 Map<Long, Double> dxy, Map<Long, Double> spx) {
        int[] aPeriods = {250, 400, 500, 750, 1000};
        int[] bPeriods = {1200, 2400, 3600, 4800, 7200, 9600};
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods, GoldTurtleDualGateStrategy.MODE_OR,
            OPPOSITE, ALIGNED, 1, 2, BOTH, "OVERLAY (1,2) — OR(A=OPP USD ferme, B=ALI stress SPX)");
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods, GoldTurtleDualGateStrategy.MODE_AND,
            OPPOSITE, ALIGNED, 1, 2, BOTH, "OVERLAY (1,2) — AND twin (quadrant USD ferme × stress SPX)");
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods, GoldTurtleDualGateStrategy.MODE_AND,
            ALIGNED, OPPOSITE, 1, 2, BOTH, "OVERLAY (1,2) — AND A=ALI B=OPP (USD faible × actions calmes)");
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods, GoldTurtleDualGateStrategy.MODE_A_ONLY,
            ALIGNED, ALIGNED, 1, 2, BOTH, "OVERLAY (1,2) — A_ONLY ALI (USD faible, mono-jambe)");
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods, GoldTurtleDualGateStrategy.MODE_OR,
            OPPOSITE, ALIGNED, 1, 2, LONG_ONLY, "OVERLAY (1,2) LONG-ONLY — OR(A=OPP, B=ALI)");
    }

    private static void sweepGrid(BacktestExecutionCost cost, List<Bar> xau,
                                  Map<Long, Double> dxy, Map<Long, Double> spx,
                                  int[] aPeriods, int[] bPeriods,
                                  int mode, int pa, int pb, int ua, int ub, int side, String title) {
        System.out.println("\n=== SWEEP 2D — " + title + " ===");
        System.out.printf("%-9s", "SMAA\\SMAB");
        for (int b : bPeriods) System.out.printf("%-12d", b);
        System.out.println();
        for (int a : aPeriods) {
            System.out.printf("%-9d", a);
            for (int b : bPeriods) {
                var s = str("d", GOLD, mode, pa, pb, a, b, ua, ub, side, dxy, spx);
                BacktestResult r = runRaw(cost, GOLD, xau, s);
                System.out.printf("%-12s", String.format("%.2f/%d", r.profitFactor(), r.totalTrades()));
            }
            System.out.println();
        }
        System.out.println("(cellule = PF/trades)");
    }

    // ---------------------------------------------------------------- walk-forward

    private static void runWalkForward(BacktestExecutionCost cost) throws Exception {
        System.out.println("=== WALK-FORWARD XAU_USD (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-36s %-12s %-6s %-6s %-6s %-7s %-12s%n",
            "CONFIG", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$");
        String[] specs = {"2006-2015", "2016-2025"};
        String[] labels = {"IS 2006-15", "OOS 2016-25"};
        for (int i = 0; i < specs.length; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            Map<Long, Double> dxy = RunGoldTurtleDualGate.buildDxy(specs[i]);
            Map<Long, Double> spx = RunGoldTurtleDualGate.buildSpxOnXauGrid(bars);
            wf(cost, "Baseline (1,0)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, BOTH, dxy, spx));
            wf(cost, "A_ONLY OPP GATE (1,0)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx));
            wf(cost, "A_ONLY OPP OVERLAY (1,2)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx));
            wf(cost, "AND twin GATE (1,0)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx));
            wf(cost, "AND twin OVERLAY (1,2)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx));
            wf(cost, "OR A=OPP B=ALI GATE (1,0)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx));
            wf(cost, "OR A=OPP B=ALI OVERLAY (1,2)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx));
            wf(cost, "OR A=OPP B=ALI OV (1,2) LONG-ONLY", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, LONG_ONLY, dxy, spx));
            wf(cost, "Baseline FLAT (2,2)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 2, 2, BOTH, dxy, spx));
            wf(cost, "Baseline FLAT (3,3)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 3, 3, BOTH, dxy, spx));
            wf(cost, "A_ONLY ALI OVERLAY (1,2)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, ALIGNED, ALIGNED, 1, 2, BOTH, dxy, spx));
            wf(cost, "AND A=ALI B=OPP OVERLAY (1,2)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, BOTH, dxy, spx));
            wf(cost, "AND A=ALI B=OPP OVERLAY (1,3)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 3, BOTH, dxy, spx));
            wf(cost, "AND A=ALI B=OPP OV (1,2) LONG-ONLY", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, LONG_ONLY, dxy, spx));
            wf(cost, "Long-only Baseline (1,0)", labels[i], bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, LONG_ONLY, dxy, spx));
        }
    }

    private static void wf(BacktestExecutionCost cost, String label, String phase,
                           List<Bar> bars, GoldTurtleDualGateStrategy s) {
        BacktestResult r = runRaw(cost, GOLD, bars, s);
        System.out.printf("%-36s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
            label, phase, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl());
    }

    // ---------------------------------------------------------------- régimes

    private static void runRegime(BacktestExecutionCost cost) throws Exception {
        String[] eras = {"bull 2006-12", "bear 2013-15", "bull2 2016-25"};
        String[] specs = {"2006-2012", "2013-2015", "2016-2025"};
        System.out.println("=== RÉGIMES ===");
        System.out.printf("%-14s %-34s %-6s %-6s %-6s %-7s %-12s%n",
            "REGIME", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int i = 0; i < eras.length; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            if (bars.isEmpty()) { System.out.println(eras[i] + " : pas de données"); continue; }
            Map<Long, Double> dxy = RunGoldTurtleDualGate.buildDxy(specs[i]);
            Map<Long, Double> spx = RunGoldTurtleDualGate.buildSpxOnXauGrid(bars);
            wfRegime(cost, eras[i], "Baseline (1,0)", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, BOTH, dxy, spx));
            wfRegime(cost, eras[i], "A_ONLY OPP OVERLAY (1,2)", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx));
            wfRegime(cost, eras[i], "OR A=OPP B=ALI GATE (1,0)", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx));
            wfRegime(cost, eras[i], "OR A=OPP B=ALI OVERLAY (1,2)", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx));
            wfRegime(cost, eras[i], "AND twin OVERLAY (1,2)", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx));
            wfRegime(cost, eras[i], "OR A=OPP B=ALI OV LONG-ONLY", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, LONG_ONLY, dxy, spx));
            wfRegime(cost, eras[i], "AND A=ALI B=OPP OVERLAY (1,2)", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, BOTH, dxy, spx));
            wfRegime(cost, eras[i], "Baseline FLAT (2,2)", bars,
                str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 2, 2, BOTH, dxy, spx));
        }
    }

    private static void wfRegime(BacktestExecutionCost cost, String era, String label,
                                 List<Bar> bars, GoldTurtleDualGateStrategy s) {
        BacktestResult r = runRaw(cost, GOLD, bars, s);
        System.out.printf("%-14s %-34s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
            era, label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl());
    }

    // ---------------------------------------------------------------- signature LONG/SHORT

    private static void runSignature(BacktestExecutionCost cost, List<Bar> xau,
                                     Map<Long, Double> dxy, Map<Long, Double> spx) {
        System.out.println("=== SIGNATURE LONG/SHORT + EXPOSITION (XAU_USD 2006-2025, coûts) ===");
        System.out.printf("%-40s %-6s %-12s %-12s %-12s %-7s %-9s%n",
            "CONFIG", "PF", "LONG$", "SHORT$", "NET$", "TRADES", "U/TRADE");
        sig(cost, "Baseline (1,0)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 1, 0, BOTH, dxy, spx), 1, 0);
        sig(cost, "Baseline FLAT (2,2)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 2, 2, BOTH, dxy, spx), 2, 2);
        sig(cost, "A_ONLY OPP GATE (1,0)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx), 1, 0);
        sig(cost, "A_ONLY OPP OVERLAY (1,2)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx), 1, 2);
        sig(cost, "AND twin GATE (1,0)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx), 1, 0);
        sig(cost, "AND twin OVERLAY (1,2)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx), 1, 2);
        sig(cost, "OR A=OPP B=ALI GATE (1,0)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 0, BOTH, dxy, spx), 1, 0);
        sig(cost, "OR A=OPP B=ALI OVERLAY (1,2)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, BOTH, dxy, spx), 1, 2);
        sig(cost, "OR A=OPP B=ALI OV (1,2) LONG-ONLY", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 1, 2, LONG_ONLY, dxy, spx), 1, 2);
        sig(cost, "AND A=ALI B=OPP OVERLAY (1,2)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, BOTH, dxy, spx), 1, 2);
        sig(cost, "AND A=ALI B=OPP OV (1,2) LONG-ONLY", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, ALIGNED, OPPOSITE, 1, 2, LONG_ONLY, dxy, spx), 1, 2);
        sig(cost, "Baseline FLAT (3,3)", xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, 3, 3, BOTH, dxy, spx), 3, 3);
    }

    private static void sig(BacktestExecutionCost cost, String label, List<Bar> bars,
                            GoldTurtleDualGateStrategy s, int ua, int ub) {
        BacktestResult r = runRaw(cost, GOLD, bars, s);
        double longUsd = 0, shortUsd = 0;
        for (com.martinfou.trading.core.Trade t : r.trades()) {
            if (t.side() == com.martinfou.trading.core.Order.Side.BUY) longUsd += t.pnl();
            else shortUsd += t.pnl();
        }
        int entries = s.entriesAllowed() + s.entriesBlocked();
        double avgUnits = entries > 0
            ? ((double) s.entriesAllowed() * ua + (double) s.entriesBlocked() * ub) / entries : 0;
        System.out.printf("%-40s %-6.2f %-12.2f %-12.2f %-12.2f %-7d %-9.2f%n",
            label, r.profitFactor(), longUsd, shortUsd, r.totalPnl(), r.totalTrades(), avgUnits);
    }

    // ---------------------------------------------------------------- helpers

    private static GoldTurtleDualGateStrategy str(String name, String symbol, int mode,
                                                  int pa, int pb, int ua, int ub, int side,
                                                  Map<Long, Double> a, Map<Long, Double> b) {
        return str(name, symbol, mode, pa, pb, SMA_A, SMA_B, ua, ub, side, a, b);
    }

    private static GoldTurtleDualGateStrategy str(String name, String symbol, int mode,
                                                  int pa, int pb, int smaA, int smaB,
                                                  int ua, int ub, int side,
                                                  Map<Long, Double> a, Map<Long, Double> b) {
        return new GoldTurtleDualGateStrategy(name, symbol, 55, 20, mode, pa, pb, smaA, smaB,
            ua, ub, side, a, b);
    }

    private static void header() {
        System.out.printf("%-40s %-6s %-6s %-6s %-7s %-12s %-8s %-8s %-9s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$", "ENTRÉES cond/¬cond");
    }

    /** Une ligne de résultat + l'exposition (part des entrées à taille pleine vs réduite). */
    private static void row(BacktestExecutionCost cost, String label, String symbol, List<Bar> bars,
                            int mode, int pa, int pb, int ua, int ub, int side,
                            Map<Long, Double> a, Map<Long, Double> b) {
        var s = str("d", symbol, mode, pa, pb, ua, ub, side, a, b);
        BacktestResult r = runRaw(cost, symbol, bars, s);
        int entries = s.entriesAllowed() + s.entriesBlocked();
        String expo = entries > 0
            ? String.format("%d/%d", s.entriesAllowed(), s.entriesBlocked()) : "-";
        System.out.printf("%-40s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-8.2f %-8.2f %-9s%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
            r.totalPnl(), r.totalReturnPct(), r.totalSwap(), expo);
    }

    private static BacktestResult runRaw(BacktestExecutionCost cost, String symbol,
                                         List<Bar> bars, com.martinfou.trading.core.Strategy s) {
        return RunContext.forStrategy(null, s.name(), s, symbol, RunMode.BACKTEST,
            bars, CAPITAL, null, cost).run();
    }
}
