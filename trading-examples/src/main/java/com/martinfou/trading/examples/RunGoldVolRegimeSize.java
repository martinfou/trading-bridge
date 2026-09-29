package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * RunGoldVolRegimeSize — MARDI 29 sept 2026 (45e résultat) — VARIATION d'une
 * stratégie existante (rotation du mardi).
 *
 * QUESTION : le modulateur de VOLATILITÉ découvert au 42e (CrossAssetVolRegime) et
 * monétisé en TAILLE au 43e (FxVolRegimeSize) est-il PROPRE AUX DEVISES, ou est-ce
 * un modulateur GÉNÉRAL de l'effet jour-de-semaine ?
 *
 * Le 43e a établi, sur le fade du vendredi FX (SELL, une jambe NÉGATIVE = prime de
 * risque) : le meilleur usage de l'information de vol est la TAILLE, pas la
 * sélection (+27.6 % de net à exposition ÉGALE vs FLAT 1.21u ; le gate jette 79 %
 * des trades). Ici on teste le JUMEAU DE SIGNE OPPOSÉ : le bid du vendredi sur l'OR
 * (LONG, PF 1.27, GoldWeekdayEffect 24e/31e), c'est-à-dire le côté refuge de la même
 * prime de risque de week-end. Prédiction si le mécanisme est un AMPLIFICATEUR DE
 * PRIME DE RISQUE (et non un effet devises) : la vol haute doit RENFORCER le bid or
 * du vendredi, donc un overlay 2u/1u doit surperformer le FLAT à exposition égale.
 *
 * Réutilise le HARNais identique au 43e, sans le modifier :
 *   - régime = rang percentile CAUSAL de la vol 21 j (traîne 1000 obs) de la VEILLE ;
 *   - proxies : EQ = vol S&P (MES D1), OWN = vol de l'instrument, AND = min, MEAN ;
 *   - benchmark obligatoire : FLAT à exposition ÉGALE (leçon du 36e) ;
 *   - contrôle contrapositif (gate bas) = discriminant gradient vs sélection (43e) ;
 *   - P0 : calibration au centime de la référence or (FRI 1.27/56.9 %/5.18 %/1041/+$15 131.24)
 *     ET de la référence FX du 43e (GBP_JPY 1.18/51 %/3.67 %/1061/+$6 854.24).
 *
 * ⚠️ Propriété spécifique de cette famille : une session = UN trade par jour cible,
 * sans report de position (masque FRI seul) ⇒ un gate ici est un FILTRE PUR, la
 * séquence n'est PAS re-timée (le caveat du 15 sept ne s'applique pas). C'est ce qui
 * autorise la décomposition exacte en quintiles.
 *
 * Usage:
 *   --base      calibration or (FRI/WED/ALLDAYS 1u) + cross-check harnais FX
 *   --quintile  GRADIENT Q1..Q5 du rang de vol (lire l'ORDRE, pas la cellule)
 *   --gate      gate haut [60,100] vs bas [0,40] (contrôle contrapositif) + mono-source
 *   --overlay   overlay 2u/1u vs FLAT à exposition ÉGALE
 *   --sweep     seuils 50/60/70/80 + 60/90 (plateau vs pic)
 *   --wf        walk-forward IS 2006-2015 / OOS 2016-2025
 *   --regime    sous-périodes bull 06-12 / taper 13-15 / bull2 16-25
 *   --control   spécificité du jour + dérive du jour + prix vs swap
 *   --all       gate + overlay + quintile + sweep + wf
 */
public class RunGoldVolRegimeSize {

    static final String GOLD = "XAU_USD";
    static final String GOLD_YEARS = "2006-2025";      // données XAU H1 → 30 déc 2025
    static final String FX_YEARS = "2006-2026";
    static final double CAPITAL = 50_000;
    static final double OZ = 10;                        // 10 oz (convention GoldTurtle)
    static final double FX_QTY = 10_000;

    static final boolean[] FRI = {false, false, false, false, true};
    static final boolean[] WED = {false, false, true, false, false};
    static final boolean[] MON_THU = {true, true, true, true, false};
    static final boolean[] ALLDAYS = {true, true, true, true, true};

    static final Map<String, List<Bar>> barsCache = new HashMap<>();
    static TreeMap<LocalDate, Double> eqRank;                 // rang vol MES (causal)
    static TreeMap<LocalDate, Double> goldOwn, goldAnd, goldMean, goldEq;
    static double[] QUINTS = {0, 20, 40, 60, 80, 100};         // bornes Q1..Q5

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        String mode = args.length > 0 ? args[0] : "--base";

        System.out.println("==============================================================");
        System.out.println("LE MODULATEUR DE VOLATILITÉ EST-IL FX-SPÉCIFIQUE ? — OR (45e)");
        System.out.println("Mode: " + mode + " | coûts $0.07 + 0.01% | capital $" + CAPITAL
            + " | " + OZ + " oz or / " + FX_QTY + " u FX");
        System.out.println("==============================================================");

        buildRegimes();

        switch (mode) {
            case "--quintile" -> quintileMode(cost);
            case "--gate" -> gateMode(cost);
            case "--overlay" -> overlayMode(cost);
            case "--sweep" -> sweepMode(cost);
            case "--wf" -> wfMode(cost);
            case "--regime" -> regimeMode(cost);
            case "--control" -> controlMode(cost);
            case "--exp" -> exposureMode(cost);
            case "--all" -> { gateMode(cost); overlayMode(cost); quintileMode(cost); sweepMode(cost); wfMode(cost); }
            default -> baseMode(cost);
        }
        System.out.println("\nDONE");
    }

    // ============================================================ RÉGIMES

    private static void buildRegimes() throws Exception {
        TreeMap<LocalDate, Double> mes = dailyClosesCsv("MES_D1.csv");
        eqRank = trailingRank(vol(rets(mes), 21), 1000);
        System.out.printf("[régime] MES vol21 rang causal : %s → %s (%d obs)%n",
            eqRank.isEmpty() ? "-" : eqRank.firstKey(), eqRank.isEmpty() ? "-" : eqRank.lastKey(), eqRank.size());

        TreeMap<LocalDate, Double> g = dailyClosesBars(GOLD, 2006, 2025);
        System.out.printf("[régime] XAU closes quotidiens : %s → %s (%d obs)%n",
            g.isEmpty() ? "-" : g.firstKey(), g.isEmpty() ? "-" : g.lastKey(), g.size());
        goldOwn = trailingRank(vol(rets(g), 21), 1000);
        goldEq = new TreeMap<>();
        goldAnd = new TreeMap<>();
        goldMean = new TreeMap<>();
        for (var e : goldOwn.entrySet()) {
            Double q = regimeAt(eqRank, e.getKey());
            if (q == null) continue;
            goldEq.put(e.getKey(), q);
            goldAnd.put(e.getKey(), Math.min(q, e.getValue()));
            goldMean.put(e.getKey(), (q + e.getValue()) / 2.0);
        }
        System.out.printf("[régime] rangs or construits : OWN %d / AND %d obs%n",
            goldOwn.size(), goldAnd.size());
    }

    /** Rang de la VEILLE (look-ahead safe) : dernière obs strictement antérieure à d. */
    static Double regimeAt(TreeMap<LocalDate, Double> rank, LocalDate d) {
        var e = rank.floorEntry(d.minusDays(1));
        return e == null ? null : e.getValue();
    }

    static TreeMap<LocalDate, Double> rankOf(String kind) {
        return switch (kind) {
            case "EQ" -> goldEq;
            case "OWN" -> goldOwn;
            case "MEAN" -> goldMean;
            default -> goldAnd;
        };
    }

    // ============================================================ CALIBRATION

    private static void baseMode(BacktestExecutionCost cost) throws Exception {
        section("P0 CALIBRATION — OR 1u (réf. 43e non-régression : FRI 1.27 / 56.9 % / 5.18 % / 1041 / +$15 131.24 ; WED 1.11)");
        System.out.printf("%-9s %-9s %6s %6s %7s %7s %11s %10s %9s%n",
            "INSTR", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "$/TRADE");
        for (String[] cfg : new String[][]{{"FRI", "FRI"}, {"WED", "WED"}, {"WED+FRI", "WF"}}) {
            var r = run(GOLD, daysOf(cfg[1]), Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false);
            line("XAU_USD", cfg[0], r);
        }
        section("P0 CROSS-CHECK HARNAIS — FX réf. 43e : GBP_JPY SELL FRI doit donner 1.18 / 51 % / 3.67 % / 1061 / +$6 854.24");
        var fx = run("GBP_JPY", FRI, Order.Side.SELL, null, null, null, FX_QTY, FX_YEARS, cost, false);
        line("GBP_JPY", "SELL FRI", fx);
    }

    // ============================================================ GATE

    private static void gateMode(BacktestExecutionCost cost) throws Exception {
        section("GATE OR — n'entrer que si le rang de la VEILLE ∈ [60,100] (haut) vs [0,40] (bas, contrôle contrapositif)");
        System.out.println("(43e : contrapositif POSITIF = gradient d'amplitude, PAS sélection. Ici gate sur une jambe LONGUE.)");
        System.out.printf("%-9s %-9s %-20s %6s %7s %11s %9s%n", "INSTR", "CONFIG", "VAR.", "PF", "TRADES", "NET$", "$/TRADE");
        for (String[] cfg : new String[][]{{"FRI", "FRI"}, {"WED", "WED"}, {"WED+FRI", "WF"}}) {
            boolean[] d = daysOf(cfg[1]);
            row("XAU_USD", cfg[0], "baseline 1u", run(GOLD, d, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false));
            row("XAU_USD", cfg[0], "GATE AND haut", run(GOLD, d, Order.Side.BUY, "AND", 60.0, 100.0, OZ, GOLD_YEARS, cost, false));
            row("XAU_USD", cfg[0], "GATE AND bas", run(GOLD, d, Order.Side.BUY, "AND", 0.0, 40.0, OZ, GOLD_YEARS, cost, false));
            row("XAU_USD", cfg[0], "GATE EQ haut", run(GOLD, d, Order.Side.BUY, "EQ", 60.0, 100.0, OZ, GOLD_YEARS, cost, false));
            row("XAU_USD", cfg[0], "GATE OWN haut", run(GOLD, d, Order.Side.BUY, "OWN", 60.0, 100.0, OZ, GOLD_YEARS, cost, false));
            System.out.println();
        }
    }

    // ============================================================ OVERLAY

    private static void overlayMode(BacktestExecutionCost cost) throws Exception {
        section("OVERLAY OR — taille 2u si rang ≥ 60 sinon 1u — vs FLAT à EXPOSITION ÉGALE (benchmark du 36e)");
        System.out.println("Si le modulateur est un amplificateur de prime de risque, le côté REFUGE (or, long) doit aussi gagner.");
        System.out.printf("%-9s %-9s %-26s %6s %6s %7s %11s %8s %9s%n",
            "INSTR", "CONFIG", "VAR.", "PF", "u/trade", "TRADES", "NET$", "DD%", "$/TRADE");
        for (String[] cfg : new String[][]{{"FRI", "FRI"}, {"WED", "WED"}}) {
            boolean[] d = daysOf(cfg[1]);
            for (String kind : new String[]{"AND", "OWN", "EQ"}) {
                var base = run(GOLD, d, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false);
                var ov = run(GOLD, d, Order.Side.BUY, kind, null, null, OZ, GOLD_YEARS, cost, true);
                double fH = highShare(d, kind, 60.0);
                double u = 1 + fH;
                var flat = run(GOLD, d, Order.Side.BUY, null, null, null, OZ * u, GOLD_YEARS, cost, false);
                line2("XAU_USD", cfg[0], "baseline 1u", base, 1.0);
                line2("XAU_USD", cfg[0], "OVERLAY 2u/1u (" + kind + ">=60)", ov, u);
                line2("XAU_USD", cfg[0], "FLAT " + String.format("%.2f", u) + "u (éq. expo)", flat, u);
                System.out.printf("   → %s | part de sessions en régime HAUT : %.1f%%%n", kind, fH * 100);
                System.out.println();
            }
        }
    }

    // ============================================================ QUINTILES

    private static void quintileMode(BacktestExecutionCost cost) throws Exception {
        section("GRADIENT — Q1..Q5 du rang de vol de la VEILLE (lecture de l'ORDRE, pas d'une cellule : pitfall du 41e)");
        System.out.println("Q1 = vol la plus BASSE … Q5 = vol la plus HAUTE. Une cellule isolée positive dans un balayage");
        System.out.println("non monotone est un test multiple, pas une découverte. Partition DÉJÀ disjointé => filtre pur.");
        for (String kind : new String[]{"AND", "EQ", "OWN", "MEAN"}) {
            for (String[] cfg : new String[][]{{"FRI", "FRI"}, {"WED", "WED"}}) {
                boolean[] d = daysOf(cfg[1]);
                System.out.printf("%n--- XAU_USD %s | proxy %s ---%n", cfg[0], kind);
                System.out.printf("%-14s %6s %6s %7s %11s %9s %9s%n", "QUINTILE", "PF", "WR%", "TRADES", "NET$", "$/TRADE", "SWAP$");
                Double prev = null;
                boolean monoUp = true, monoDn = true;
                for (int i = 0; i < 5; i++) {
                    double lo = QUINTS[i], hi = i == 4 ? 100.0 : QUINTS[i + 1] - 0.0001;
                    var r = run(GOLD, d, Order.Side.BUY, kind, lo, hi, OZ, GOLD_YEARS, cost, false);
                    double per = r == null || r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades();
                    System.out.printf("%-14s %6.2f %5.1f%% %7d %11.2f %9.2f %9.2f%n",
                        String.format("Q%d [%.0f-%.1f]", i + 1, lo, hi), r == null ? 0 : r.profitFactor(),
                        r == null ? 0 : r.winRatePct(), r == null ? 0 : r.totalTrades(),
                        r == null ? 0 : r.totalPnl(), per, r == null ? 0 : r.totalSwap());
                    if (prev != null) {
                        if (per < prev) monoUp = false;
                        if (per > prev) monoDn = false;
                    }
                    prev = per;
                }
                System.out.printf("   → ordre : %s%n", monoUp ? "MONOTONE CROISSANT (vol haute = plus fort)"
                    : monoDn ? "MONOTONE DÉCROISSANT" : "NON MONOTONE");
            }
        }
    }

    // ============================================================ SWEEP

    private static void sweepMode(BacktestExecutionCost cost) throws Exception {
        section("ROBUSTESSE PARAMÉTRIQUE — balayage du seuil (plateau = robuste, pic = curve fitting)");
        for (String[] cfg : new String[][]{{"FRI", "FRI"}, {"WED", "WED"}}) {
            boolean[] d = daysOf(cfg[1]);
            System.out.printf("%n--- XAU_USD %s — GATE AND [seuil-100] ---%n", cfg[0]);
            System.out.printf("%-18s %6s %7s %11s %9s%n", "SEUIL", "PF", "TRADES", "NET$", "$/TRADE");
            for (double th : new double[]{50, 60, 70, 80, 90}) {
                var r = run(GOLD, d, Order.Side.BUY, "AND", th, 100.0, OZ, GOLD_YEARS, cost, false);
                row4(String.format("GATE AND >= %.0f", th), r);
            }
            System.out.printf("%n--- XAU_USD %s — GATE BAS (contrapositif, on n'entre que SOUS le seuil) ---%n", cfg[0]);
            System.out.printf("%-18s %6s %7s %11s %9s%n", "SEUIL", "PF", "TRADES", "NET$", "$/TRADE");
            for (double th : new double[]{20, 30, 40, 50}) {
                var r = run(GOLD, d, Order.Side.BUY, "AND", 0.0, th, OZ, GOLD_YEARS, cost, false);
                row4(String.format("GATE AND <= %.0f", th), r);
            }
        }
    }

    // ============================================================ WALK-FORWARD

    private static void wfMode(BacktestExecutionCost cost) throws Exception {
        section("WALK-FORWARD — IS 2006-2015 / OOS 2016-2025 (données XAU → 30 déc 2025)");
        System.out.printf("%-9s %-9s %-16s %-20s %6s %7s %11s%n",
            "INSTR", "CONFIG", "PÉRIODE", "VAR.", "PF", "TRADES", "NET$");
        for (String[] cfg : new String[][]{{"FRI", "FRI"}, {"WED", "WED"}}) {
            boolean[] d = daysOf(cfg[1]);
            for (String[] p : new String[][]{{"IS 2006-2015", "2006-2015"}, {"OOS 2016-2025", "2016-2025"}}) {
                row3("XAU_USD", cfg[0], p, "baseline 1u", run(GOLD, d, Order.Side.BUY, null, null, null, OZ, p[1], cost, false));
                row3("XAU_USD", cfg[0], p, "GATE AND haut", run(GOLD, d, Order.Side.BUY, "AND", 60.0, 100.0, OZ, p[1], cost, false));
                row3("XAU_USD", cfg[0], p, "OVERLAY 2u/1u", run(GOLD, d, Order.Side.BUY, "AND", null, null, OZ, p[1], cost, true));
                System.out.println();
            }
        }
    }

    // ============================================================ RÉGIMES

    private static void regimeMode(BacktestExecutionCost cost) throws Exception {
        section("RÉGIMES DE MARCHÉ — bull 2006-2012 / taper 2013-2015 / bull2 2016-2025 (FRI)");
        System.out.printf("%-14s %-20s %6s %7s %11s%n", "RÉGIME", "VAR.", "PF", "TRADES", "NET$");
        for (String[] rg : new String[][]{{"bull 2006-2012", "2006-2012"}, {"taper 2013-2015", "2013-2015"}, {"bull2 2016-2025", "2016-2025"}}) {
            row3("XAU_USD", "FRI", rg, "baseline 1u", run(GOLD, FRI, Order.Side.BUY, null, null, null, OZ, rg[1], cost, false));
            row3("XAU_USD", "FRI", rg, "GATE AND haut", run(GOLD, FRI, Order.Side.BUY, "AND", 60.0, 100.0, OZ, rg[1], cost, false));
            row3("XAU_USD", "FRI", rg, "OVERLAY 2u/1u", run(GOLD, FRI, Order.Side.BUY, "AND", null, null, OZ, rg[1], cost, true));
            System.out.println();
        }
    }

    // ============================================================ CONTRÔLES

    private static void controlMode(BacktestExecutionCost cost) throws Exception {
        section("CONTRÔLE 1 — SPÉCIFICITÉ DU JOUR : le même gate/overlay appliqué à LUNDI-JEUDI doit échouer");
        System.out.printf("%-22s %6s %7s %11s %9s%n", "VAR. (Mon-Jeu, BUY or)", "PF", "TRADES", "NET$", "$/TRADE");
        row4("Mon-Jeu baseline 1u", run(GOLD, MON_THU, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false));
        row4("Mon-Jeu GATE AND haut", run(GOLD, MON_THU, Order.Side.BUY, "AND", 60.0, 100.0, OZ, GOLD_YEARS, cost, false));
        row4("Mon-Jeu OVERLAY 2u/1u", run(GOLD, MON_THU, Order.Side.BUY, "AND", null, null, OZ, GOLD_YEARS, cost, true));

        section("CONTRÔLE 2 — DÉRIVE DU JOUR (bêta non conditionnée) : FRI vs Mon-Jeu vs ALLDAYS");
        System.out.printf("%-22s %6s %7s %11s %9s%n", "MODE (BUY or)", "PF", "TRADES", "NET$", "$/TRADE");
        row4("BUY FRI", run(GOLD, FRI, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false));
        row4("BUY WED", run(GOLD, WED, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false));
        row4("BUY Mon-Jeu", run(GOLD, MON_THU, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false));
        row4("BUY ALLDAYS", run(GOLD, ALLDAYS, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false));

        section("CONTRÔLE 3 — PART DE L'EDGE PAR TRADE : jambe de PRIX seule (net − swap) — or : swap attendu ≈ 0");
        System.out.printf("%-22s %10s %10s %11s%n", "VAR.", "PRIX$/trade", "SWAP$/trade", "NET$/trade");
        prix("FRI baseline 1u", run(GOLD, FRI, Order.Side.BUY, null, null, null, OZ, GOLD_YEARS, cost, false));
        prix("FRI OVERLAY 2u/1u", run(GOLD, FRI, Order.Side.BUY, "AND", null, null, OZ, GOLD_YEARS, cost, true));
        prix("WED OVERLAY 2u/1u", run(GOLD, WED, Order.Side.BUY, "AND", null, null, OZ, GOLD_YEARS, cost, true));

        section("CONTRÔLE 4 — LE MÊME MODULATEUR SUR LE CÔTÉ FX (rappel du 43e) : SELL FRI GBP_JPY");
        System.out.printf("%-22s %6s %7s %11s %9s%n", "VAR. (GBP_JPY)", "PF", "TRADES", "NET$", "$/TRADE");
        row4("baseline 1u", run("GBP_JPY", FRI, Order.Side.SELL, null, null, null, FX_QTY, FX_YEARS, cost, false));
        System.out.println("(la mécanique FX exige le régime FX, construit dans RunFxVolRegimeSize — non re-testé ici)");
    }

    // ============================================================ EXPOSITION (contrôle décisif IS/OOS)

    private static void exposureMode(BacktestExecutionCost cost) throws Exception {
        section("CONTRÔLE DÉCISIF — OVERLAY vs LEVIER UNIFORME À EXPOSITION ÉGALE, PAR PÉRIODE (IS / OOS / FULL)");
        System.out.println("PF invariant à la quantité ⇒ net(FLAT u) ≈ u × net(baseline 1u). L'overlay ne gagne QUE si");
        System.out.println("son net > le net du FLAT au même u. Un gain full-sample qui disparaît en OOS = artefact d'ère.");
        System.out.printf("%-9s %-16s %-26s %6s %6s %7s %11s %10s %9s%n",
            "CONFIG", "PÉRIODE", "VAR.", "PF", "u", "TRADES", "NET$", "$/TRADE", "NET$/u");
        for (String[] cfg : new String[][]{{"FRI", "FRI"}, {"WED", "WED"}}) {
            boolean[] d = daysOf(cfg[1]);
            for (String[] p : new String[][]{{"FULL 2006-2025", GOLD_YEARS}, {"IS 2006-2015", "2006-2015"}, {"OOS 2016-2025", "2016-2025"}}) {
                for (String kind : new String[]{"AND", "EQ"}) {
                    var base = run(GOLD, d, Order.Side.BUY, null, null, null, OZ, p[1], cost, false);
                    var ov = run(GOLD, d, Order.Side.BUY, kind, null, null, OZ, p[1], cost, true);
                    double fH = highShareFor(d, kind, 60.0, p[1]);
                    double u = 1 + fH;
                    var flat = run(GOLD, d, Order.Side.BUY, null, null, null, OZ * u, p[1], cost, false);
                    double dPct = flat.totalPnl() != 0 ? 100.0 * (ov.totalPnl() - flat.totalPnl()) / Math.abs(flat.totalPnl()) : 0;
                    line3(cfg[0], p[0], "baseline 1u", base, 1.0);
                    line3(cfg[0], p[0], "FLAT " + String.format("%.3f", u) + "u (éq. expo)", flat, u);
                    line3(cfg[0], p[0], "OVERLAY 2u/1u (" + kind + ")", ov, u);
                    System.out.printf("   → %s %s : OVERLAY %s FLAT à exposition ÉGALE (%+.1f %%)%n",
                        cfg[0], p[0], dPct >= 0 ? "BAT" : "PERD CONTRE", dPct);
                    System.out.println();
                }
            }
        }
    }

    /** Part des sessions cibles en régime haut pour une période donnée (causal). */
    private static double highShareFor(boolean[] days, String kind, double threshold, String yearSpec) {
        TreeMap<LocalDate, Double> ranks = rankOf(kind);
        List<Bar> bars = barsCache.get(GOLD + "|" + yearSpec);
        if (bars == null) return 0;
        Set<LocalDate> target = new TreeSet<>();
        ZoneId utc = ZoneId.of("UTC");
        for (Bar b : bars) {
            LocalDate dd = b.timestamp().atZone(utc).toLocalDate();
            int idx = dd.getDayOfWeek().getValue() - 1;
            if (idx >= 0 && idx < 5 && days[idx]) target.add(dd);
        }
        int n = 0, hi = 0;
        for (LocalDate dd : target) {
            Double r = regimeAt(ranks, dd);
            if (r == null) continue;
            n++;
            if (r >= threshold) hi++;
        }
        return n == 0 ? 0 : hi / (double) n;
    }

    private static void line3(String cfg, String period, String variant, BacktestResult r, double u) {
        System.out.printf("%-9s %-16s %-26s %6.2f %6.3f %7d %11.2f %10.2f %9.2f%n",
            cfg, period, variant, r.profitFactor(), u, r.totalTrades(), r.totalPnl(),
            r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades(), r.totalPnl() / u);
    }

    // ============================================================ HELPERS AFFICHAGE

    private static boolean[] daysOf(String code) {
        return switch (code) {
            case "FRI" -> FRI;
            case "WED" -> WED;
            case "WF" -> new boolean[]{false, false, true, false, true};
            default -> ALLDAYS;
        };
    }

    private static void section(String t) {
        System.out.println("\n==============================================================");
        System.out.println(t);
        System.out.println("==============================================================");
    }

    private static void line(String sym, String cfg, BacktestResult r) {
        if (r == null) { System.out.printf("%-9s %-9s PAS DE DONNÉES%n", sym, cfg); return; }
        System.out.printf("%-9s %-9s %6.2f %5.1f%% %5.2f%% %7d %11.2f %10.2f %9.2f%n",
            sym, cfg, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
            r.totalPnl(), r.totalSwap(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
    }

    private static void line2(String sym, String cfg, String variant, BacktestResult r, double u) {
        System.out.printf("%-9s %-9s %-26s %6.2f %6.2f %7d %11.2f %7.2f%% %9.2f%n",
            sym, cfg, variant, r.profitFactor(), u, r.totalTrades(), r.totalPnl(), r.maxDrawdownPct(),
            r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
    }

    private static void row(String sym, String cfg, String variant, BacktestResult r) {
        if (r == null) { System.out.printf("%-9s %-9s %-20s PAS DE DONNÉES%n", sym, cfg, variant); return; }
        System.out.printf("%-9s %-9s %-20s %6.2f %7d %11.2f %9.2f%n",
            sym, cfg, variant, r.profitFactor(), r.totalTrades(), r.totalPnl(),
            r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
    }

    private static void row3(String sym, String cfg, String[] period, String variant, BacktestResult r) {
        System.out.printf("%-9s %-9s %-16s %-20s %6.2f %7d %11.2f%n",
            sym, cfg, period[0], variant, r.profitFactor(), r.totalTrades(), r.totalPnl());
    }

    private static void row4(String label, BacktestResult r) {
        System.out.printf("%-22s %6.2f %7d %11.2f %9.2f%n", label, r.profitFactor(), r.totalTrades(),
            r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
    }

    private static void prix(String label, BacktestResult r) {
        int n = r.totalTrades();
        System.out.printf("%-22s %10.2f %10.2f %11.2f%n", label,
            n == 0 ? 0 : (r.totalPnl() - r.totalSwap()) / n, n == 0 ? 0 : r.totalSwap() / n,
            n == 0 ? 0 : r.totalPnl() / n);
    }

    /** Part des sessions cibles en régime haut (rang veille ≥ seuil). Causal. */
    private static double highShare(boolean[] days, String kind, double threshold) {
        TreeMap<LocalDate, Double> ranks = rankOf(kind);
        List<Bar> bars = barsCache.get(GOLD + "|" + GOLD_YEARS);
        if (bars == null) return 0;
        Set<LocalDate> target = new TreeSet<>();
        ZoneId utc = ZoneId.of("UTC");
        for (Bar b : bars) {
            LocalDate d = b.timestamp().atZone(utc).toLocalDate();
            int idx = d.getDayOfWeek().getValue() - 1;
            if (idx >= 0 && idx < 5 && days[idx]) target.add(d);
        }
        int n = 0, hi = 0;
        for (LocalDate d : target) {
            Double r = regimeAt(ranks, d);
            if (r == null) continue;
            n++;
            if (r >= threshold) hi++;
        }
        return n == 0 ? 0 : hi / (double) n;
    }

    // ============================================================ EXÉCUTION

    private static BacktestResult run(String symbol, boolean[] days, Order.Side side, String kind,
                                      Double gateLo, Double gateHi, double qty, String yearSpec,
                                      BacktestExecutionCost cost, boolean overlay) throws Exception {
        String key = symbol + "|" + yearSpec;
        List<Bar> bars = barsCache.get(key);
        if (bars == null) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
            bars = loaded.bars();
            barsCache.put(key, bars);
        }
        if (bars.isEmpty()) return null;
        var strategy = new GoldWeekdayEffectStrategy("WeekdayVolRegime", symbol, days, side)
            .withQuantity(qty);
        if (kind != null) {
            var rank = rankOf(kind);
            if (symbol.equals(GOLD)) {
                strategy.withRegime(rank);
                if (overlay) {
                    strategy.withOverlay(1.0, 2.0, 60.0);
                } else if (gateLo != null && gateHi != null) {
                    strategy.withGate(gateLo, gateHi);
                }
            }
        }
        return RunContext.forStrategy(null, "WeekdayVolRegime", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    // ------------------------------------------------------------ données

    static TreeMap<LocalDate, Double> dailyClosesCsv(String csv) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (String line : Files.readAllLines(Path.of("data/historical/futures").resolve(csv))) {
            String[] f = line.split(",");
            if (f.length < 5 || f[0].startsWith("Date")) continue;
            try { map.put(LocalDate.parse(f[0].trim()), Double.parseDouble(f[4])); } catch (Exception ignore) {}
        }
        return map;
    }

    static TreeMap<LocalDate, Double> dailyClosesBars(String symbol, int from, int to) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get("data/historical/bars"));
                if (bars == null) continue;
                for (Bar b : bars) map.put(b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), b.close());
            } catch (Exception ignored) { }
        }
        return map;
    }

    static TreeMap<LocalDate, Double> rets(TreeMap<LocalDate, Double> m) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        LocalDate prev = null;
        for (var e : m.entrySet()) {
            if (prev != null && m.get(prev) > 0) out.put(e.getKey(), (e.getValue() - m.get(prev)) / m.get(prev));
            prev = e.getKey();
        }
        return out;
    }

    static TreeMap<LocalDate, Double> vol(TreeMap<LocalDate, Double> rets, int win) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<Double> buf = new ArrayList<>();
        for (var e : rets.entrySet()) {
            buf.add(e.getValue());
            if (buf.size() > win) buf.remove(0);
            if (buf.size() == win) {
                double m = buf.stream().mapToDouble(d -> d).average().orElse(0);
                double v = buf.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / (buf.size() - 1);
                out.put(e.getKey(), Math.sqrt(v));
            }
        }
        return out;
    }

    /** Rang percentile CAUSAL (0-100) : position de la valeur dans les `trail` obs PRÉCÉDENTES. */
    static TreeMap<LocalDate, Double> trailingRank(TreeMap<LocalDate, Double> s, int trail) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<Double> buf = new ArrayList<>();
        for (var e : s.entrySet()) {
            if (!buf.isEmpty()) {
                int n = Math.min(trail, buf.size());
                List<Double> win = buf.subList(buf.size() - n, buf.size());
                long le = win.stream().filter(d -> d <= e.getValue()).count();
                out.put(e.getKey(), 100.0 * le / n);
            }
            buf.add(e.getValue());
        }
        return out;
    }
}
