package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.SwapCalculator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.util.*;

/**
 * RunFxFridayCarryReal — JEUDI 1er oct 2026 (47e résultat, axe INTERMARKET / CROSS-ASSET).
 *
 * QUESTION : le fade du vendredi FX (37e : SELL vendredi, PF 1.18 GBP_JPY) a été REJETÉ en panier
 * au 43e « parce que les -$19K à -$21K de swap constant 2024-26 tuent AUD/NZD/CHF/JPY ». Or le
 * 44e (28 sept) a montré que la table de swap du repo est un ARTEFACT : taux CONSTANTS 2024-2026
 * appliqués à 2006-2026, dont le SIGNE change d'ère. Le 43e a donc imputé au marché une perte qui
 * appartient au modèle de coût.
 *
 * LIVRABLE : construire la table de carry RÉELLE des 8 devises (taux à 3 mois interbancaires,
 * moyennes annuelles, source FRED/OCDE IR3TIB01) et re-tester le panier sous 4 hypothèses :
 *   ZERO          swap = 0                → isole la jambe de PRIX (calibration)
 *   REPO          table du repo (constantes) → doit reproduire la référence du 43e
 *   RÉEL          différentiel annuel réel, 0 spread broker
 *   RÉEL + 0.25   0.25 pip/lot/jour de demi-spread (markup courtier)
 *
 * CONVERSION : 1 % de différentiel annuel = 1 000 $/an sur 100 000 unités = 2.74 $/jour
 *              = 0.274 pip/lot/jour (pip = 10 unités de cote). Taux du repo : 1 % = 0.274 pip/j.
 *
 * SOURCES (famille UNIFORME 3 mois interbancaire, moyennes mensuelles, 2006 → 2026, FRED) :
 *   USD IR3TIB01USM156N · EUR IR3TIB01EZM156N · GBP IR3TIB01GBM156N · JPY IR3TIB01JPM156N
 *   AUD IR3TIB01AUM156N · NZD IR3TIB01NZM156N · CAD IR3TIB01CAM156N · CHF IR3TIB01CHM156N
 *   (famille POLICY, utilisée au 44e : FEDFUNDS / ECBDFR / IUDSOIA / … — contrôle de sensibilité)
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunFxFridayCarryReal --table
 *   ... --basket | --breakeven | --era | --family | --all
 */
public class RunFxFridayCarryReal {

    static final String YEAR_SPEC = "2006-2026";
    static final double CAPITAL = 50_000;
    static final double QTY = 10_000;              // unités FX = taille du 37e/43e
    static final double PIP_PER_PCT = 0.274;       // 1 % annuel de différentiel = 0.274 pip/lot/jour
    static final double HALF_SPREAD = 0.25;        // markup courtier (pip/lot/jour, chaque jambe)

    static final String[] ALL8 = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY", "USD_CAD"};
    static final String[] BASKET7 = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY"};
    static final boolean[] FRI = {false, false, false, false, true};

    // ---------------------------------------------------------------- tables de taux

    /** Différentiel annuel (BASE − QUOTE) en %, 2006 → 2026 — famille UNIFORME 3M interbancaire. */
    static final Map<String, double[]> DIFF_U = new LinkedHashMap<>();
    /** Même chose, famille POLICY du 44e (contrôle de sensibilité sur 3 paires). */
    static final Map<String, double[]> DIFF_P = new LinkedHashMap<>();

    static {
        DIFF_U.put("GBP_JPY", new double[]{+4.524, +5.256, +4.665, +0.635, +0.317, +0.542, +0.500, +0.276, +0.338, +0.404, +0.425, +0.300, +0.650, +0.777, +0.330, +0.155, +2.030, +4.947, +4.816, +3.311, +2.424});
        DIFF_U.put("GBP_USD", new double[]{-0.304, +0.734, +2.546, +0.658, +0.388, +0.571, +0.545, +0.346, +0.419, +0.347, -0.145, -0.794, -1.466, -1.400, -0.239, -0.024, -0.227, -0.227, -0.049, -0.047, -0.025});
        DIFF_U.put("AUD_USD", new double[]{+0.835, +1.399, +4.008, +2.878, +4.369, +4.544, +3.446, +2.611, +2.553, +2.029, +1.342, +0.587, -0.240, -0.863, -0.277, -0.086, -0.591, -1.198, -0.660, -0.347, +0.533});
        DIFF_U.put("NZD_USD", new double[]{+2.393, +3.065, +5.057, +2.480, +2.689, +2.497, +2.389, +2.490, +3.300, +3.014, +1.686, +0.805, -0.243, -0.689, -0.052, +0.374, +0.538, +0.301, +0.208, -0.965, -1.085});
        DIFF_U.put("USD_CHF", new double[]{+3.742, +2.925, +1.124, +0.462, +0.162, +0.246, +0.344, +0.214, +0.123, +0.951, +1.413, +2.000, +2.995, +2.981, +1.231, +0.850, +2.405, +3.656, +3.809, +4.084, +3.777});
        DIFF_U.put("EUR_USD", new double[]{-2.074, -0.991, +1.669, +0.672, +0.499, +1.087, +0.291, +0.054, +0.086, -0.246, -0.908, -1.482, -2.510, -2.564, -0.959, -0.663, -1.886, -1.747, -1.480, -1.993, -1.707});
        DIFF_U.put("USD_JPY", new double[]{+4.829, +4.522, +2.118, -0.023, -0.072, -0.029, -0.045, -0.070, -0.081, +0.057, +0.571, +1.093, +2.116, +2.176, +0.569, +0.180, +2.257, +5.174, +4.865, +3.358, +2.449});
        DIFF_U.put("USD_CAD", new double[]{+1.144, +1.126, +0.628, +0.224, -0.250, -0.608, -0.659, -0.799, -0.785, -0.301, +0.158, +0.464, +0.815, +0.552, +0.082, +0.001, +0.050, +0.437, +0.684, +1.592, +1.488});

        DIFF_P.put("GBP_JPY", new double[]{+4.348, +4.839, +3.746, -0.030, +0.105, +0.193, +0.139, +0.191, +0.221, +0.286, +0.286, +0.192, +0.487, +0.678, +0.224, +0.121, +1.435, +4.610, +4.870, +3.401, +2.443});
        DIFF_P.put("AUD_USD", new double[]{+1.024, +1.649, +5.046, +3.274, +4.506, +4.745, +3.589, +2.670, +2.589, +2.123, +1.592, +0.738, +0.116, -0.814, -0.119, -0.052, -0.046, -1.045, -0.752, -0.387, +0.633});
        DIFF_P.put("USD_CAD", RunUsdcadSwapRetest.DIFF);
        DIFF_P.put("GBP_USD", new double[]{-0.291, +0.566, +2.666, +0.388, +0.313, +0.424, +0.326, +0.321, +0.337, +0.323, -0.035, -0.751, -1.273, -1.449, -0.187, -0.025, -0.278, -0.410, -0.087, +0.003, +0.094});
        DIFF_P.put("NZD_USD", new double[]{+2.582, +3.314, +6.095, +2.876, +2.826, +2.698, +2.532, +2.550, +3.335, +3.108, +1.935, +0.955, +0.113, -0.640, +0.106, +0.408, +1.083, +0.454, +0.115, -1.005, -0.985});
        DIFF_P.put("USD_CHF", new double[]{+3.552, +2.676, +0.086, +0.066, +0.025, +0.044, +0.202, +0.154, +0.088, +0.858, +1.163, +1.850, +2.638, +2.931, +1.074, +0.816, +1.860, +3.503, +3.902, +4.124, +3.677});
        DIFF_P.put("EUR_USD", new double[]{-3.204, -2.180, +1.078, +0.306, +0.075, +0.397, -0.009, -0.107, -0.176, -0.339, -0.775, -1.402, -2.232, -2.587, -0.876, -0.580, -1.604, -1.716, -1.411, -1.953, -1.524});
        DIFF_P.put("USD_JPY", new double[]{+4.639, +4.273, +1.080, -0.418, -0.208, -0.231, -0.187, -0.130, -0.116, -0.037, +0.321, +0.943, +1.760, +2.127, +0.411, +0.146, +1.713, +5.020, +4.957, +3.398, +2.349});
    }

    /** Table par année pour le moteur : long = +0.274·diff, short = −0.274·diff, moins le demi-spread. */
    static Map<Integer, double[]> yearlyTable(double[] diffs, double halfSpread) {
        Map<Integer, double[]> m = new HashMap<>();
        for (int i = 0; i < diffs.length; i++) {
            double pip = PIP_PER_PCT * diffs[i];
            m.put(2006 + i, new double[]{pip - halfSpread, -pip - halfSpread});
        }
        m.put(-1, m.get(2026));
        return m;
    }

    static double meanAbs(double[] d, int from, int to) {
        double s = 0; int n = 0;
        for (int i = from; i <= to && i < d.length; i++) { s += Math.abs(d[i]); n++; }
        return n == 0 ? 0 : s / n;
    }

    static double signedMean(double[] d, int from, int to) {
        double s = 0; int n = 0;
        for (int i = from; i <= to && i < d.length; i++) { s += d[i]; n++; }
        return n == 0 ? 0 : s / n;
    }

    // ---------------------------------------------------------------- hypothèses de swap

    record Hyp(String label, Map<Integer, double[]> table, Double flatLong, Double flatShort) {
        boolean isZero() { return flatLong != null && flatLong == 0.0 && flatShort == 0.0; }
    }

    static final Hyp ZERO = new Hyp("ZERO (jambe de PRIX)", null, 0.0, 0.0);
    static final Hyp REPO = new Hyp("REPO (constantes 2024-26)", null, null, null);

    static void apply(String sym, Hyp h) {
        if (h.table() != null) SwapCalculator.setYearlyRateOverride(sym, h.table());
        else if (h.flatLong() != null) SwapCalculator.setRateOverride(sym, h.flatLong(), h.flatShort());
        else SwapCalculator.clearRateOverride();      // table du repo
    }

    static Hyp real(String sym, double halfSpread) {
        return new Hyp(String.format("RÉEL 3M %s%.2f", halfSpread > 0 ? "+" : "", halfSpread),
            yearlyTable(DIFF_U.get(sym), halfSpread), null, null);
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--table";
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.println("==============================================================");
        System.out.println("FADE DU VENDREDI FX × CARRY RÉEL DES 8 DEVISES (47e, jeudi cross-asset)");
        System.out.println("Mode: " + mode + " | SELL vendredi 10 000 u | coûts $0.07 + 0.01% | capital $" + CAPITAL);
        System.out.println("==============================================================");

        switch (mode) {
            case "--basket" -> basket(cost);
            case "--breakeven" -> breakeven(cost);
            case "--era" -> era(cost);
            case "--family" -> family(cost);
            case "--overlay" -> overlay(cost);
            case "--all" -> { table(); basket(cost); breakeven(cost); era(cost); family(cost); overlay(cost); }
            default -> table();
        }
        SwapCalculator.clearRateOverride();
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- 1. table

    static void table() {
        section("1) TABLE DE CARRY RÉELLE — famille UNIFORME IR3TIB01 (3 mois interbancaire), 2006-2026");
        System.out.printf("%-9s %9s %9s %9s %11s%n",
            "PAIRE", "moy06-15", "moy16-26", "|diff|moy", "pip/j LONG");
        for (String sym : ALL8) {
            double[] d = DIFF_U.get(sym);
            System.out.printf("%-9s %+8.2f%% %+8.2f%% %8.3f%% %11.3f%n",
                sym, signedMean(d, 0, 9), signedMean(d, 10, 20), meanAbs(d, 0, 19),
                PIP_PER_PCT * signedMean(d, 0, 19));
        }
        System.out.println("Lecture : pip/j réel = 0.274 × différentiel moyen signé (BASE − QUOTE), 2006-2025.");
        System.out.println("La colonne REPO(sell) = taux SHORT de la table du repo (négatif = débit) : à comparer au débit réel.");
        System.out.printf("%-9s %12s %12s %12s%n", "", "pip/j REPO(SELL)", "pip/j RÉEL(SELL)", "ratio REPO/réel");
        for (String sym : ALL8) {
            double repo = SwapCalculator.getShortSwap(sym);
            double reel = -PIP_PER_PCT * signedMean(DIFF_U.get(sym), 0, 19);
            System.out.printf("%-9s %12.2f %12.3f %12s%n", sym, repo, reel,
                Math.abs(reel) < 0.01 ? "n/a" : String.format("%+.1f×", repo / reel));
        }
        System.out.println("\nSensibilité à la FAMILLE de taux (uniforme 3M vs policy du 44e), |diff| moyen :");
        for (String sym : new String[]{"GBP_JPY", "AUD_USD", "USD_CAD", "GBP_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY"}) {
            double u = meanAbs(DIFF_U.get(sym), 0, 19), p = meanAbs(DIFF_P.get(sym), 0, 19);
            System.out.printf("   %-9s uniforme %.3f  vs  policy %.3f   (écart %+.3f pt = %.3f pip/j)%n",
                sym, u, p, u - p, PIP_PER_PCT * (u - p));
        }
    }

    static double getRepoShort(String sym) {
        double s = SwapCalculator.getShortSwap(sym);
        return s;
    }

    // ---------------------------------------------------------------- 2. panier

    static void basket(BacktestExecutionCost cost) throws Exception {
        section("2) PANIER — 8 paires sous 4 hypothèses de swap (SELL FRI 1u)");
        System.out.printf("%-9s %-22s %6s %5s %6s %7s %11s %10s %10s %9s%n",
            "PAIRE", "HYPOTHÈSE", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "PRIX$", "$/TRADE");
        Map<String, double[]> agg = new LinkedHashMap<>();   // label -> {net, swap, trades, pairsPF12, pairsNetPos}
        for (String sym : ALL8) {
            for (Hyp h : new Hyp[]{ZERO, REPO, real(sym, 0.0), real(sym, HALF_SPREAD)}) {
                apply(sym, h);
                BacktestResult r = run(sym, YEAR_SPEC, cost);
                if (r == null) { System.out.printf("%-9s PAS DE DONNÉES%n", sym); continue; }
                double price = r.totalPnl() - r.totalSwap();
                System.out.printf("%-9s %-22s %6.2f %4.0f%% %5.2f%% %7d %11.2f %10.2f %10.2f %9.2f%n",
                    sym, h.label(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalSwap(), price, r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
                if (Arrays.asList(BASKET7).contains(sym)) {
                    double[] a = agg.computeIfAbsent(h.label(), k -> new double[6]);
                    a[0] += r.totalPnl(); a[1] += r.totalSwap(); a[2] += r.totalTrades();
                    if (r.profitFactor() >= 1.2) a[3]++;
                    if (r.totalPnl() > 0) a[4]++;
                }
            }
            System.out.println();
        }
        SwapCalculator.clearRateOverride();
        section("AGRÉGAT PANIER 7 PAIRES (hors USD_CAD, contrôle interne du 37e)");
        System.out.printf("%-22s %11s %10s %8s %8s %11s %11s%n",
            "HYPOTHÈSE", "NET$", "SWAP$", "TRADES", "$/TRADE", "PF>=1.2", "NET>0");
        for (var e : agg.entrySet()) {
            double[] a = e.getValue();
            System.out.printf("%-22s %11.2f %10.2f %8.0f %8.2f %8.0f/7 %8.0f/7%n",
                e.getKey(), a[0], a[1], a[2], a[2] == 0 ? 0 : a[0] / a[2], a[3], a[4]);
        }
    }

    // ---------------------------------------------------------------- 3. seuil de rupture

    static void breakeven(BacktestExecutionCost cost) throws Exception {
        section("3) SEUIL DE RUPTURE PAR PAIRE (méthode du 44e) — débit max supportable, en pip/lot/jour");
        System.out.println("Probe : swap SHORT = 1.0 pip/lot/jour ⇒ totalSwap$ = 1.0 × pipValue × jours de rollover");
        System.out.println("⇒ J = totalSwap$/pipValue ; seuil = netPRIX$ / (pipValue × J).");
        System.out.println("carry SELL réel = −0.274 × différentiel signé (PAIE = le fade vendredi paie le carry).");
        System.out.printf("%-9s %10s %11s %12s %16s %10s %9s%n",
            "PAIRE", "PRIX net$", "J rollovers", "seuil pip/j", "carry SELL pip/j", "effet", "marge");
        for (String sym : ALL8) {
            apply(sym, ZERO);
            BacktestResult zero = run(sym, YEAR_SPEC, cost);
            apply(sym, new Hyp("probe", null, 0.0, 1.0));
            BacktestResult probe = run(sym, YEAR_SPEC, cost);
            double pipValue = sym.contains("JPY") ? QTY * 0.01 / 150.0 : QTY * 0.0001;
            double j = probe.totalSwap() / pipValue;
            double price = zero.totalPnl();
            double seuil = j == 0 ? Double.NaN : price / (pipValue * j);
            double sellPip = -PIP_PER_PCT * signedMean(DIFF_U.get(sym), 0, 19);
            String effet = sellPip < 0 ? "PAIE" : "ENCAISSE";
            String marge;
            if (sellPip >= 0) marge = "∞ (crédit)";
            else if (seuil <= 0) marge = "0 (prix<0)";
            else marge = String.format("%.2f×", seuil / Math.abs(sellPip));
            System.out.printf("%-9s %10.2f %11.0f %12.3f %16.3f %10s %9s%n",
                sym, price, j, seuil, sellPip, effet, marge);
        }
        SwapCalculator.clearRateOverride();
        System.out.println("\nMarge < 1× ⇒ la paire meurt sur le carry réel ; marge = ∞ ⇒ le fade est PAYÉ pour");
        System.out.println("tenir la position (encaisse le carry en vendant la devise à taux élevé).");
    }

    // ---------------------------------------------------------------- 4. ères

    static void era(BacktestExecutionCost cost) throws Exception {
        section("4) ÈRES — 2006-2015 vs 2016-2026 (le signe du carry change d'ère)");
        System.out.printf("%-9s %-12s %10s %10s %10s %10s %10s%n",
            "PAIRE", "ÈRE", "PF_REPO", "NET_REPO", "SWAP_REPO", "PF_RÉEL", "NET_RÉEL");
        for (String sym : ALL8) {
            for (String spec : new String[]{"2006-2015", "2016-2026"}) {
                apply(sym, ZERO);
                BacktestResult z = run(sym, spec, cost);
                apply(sym, REPO);
                BacktestResult rr = run(sym, spec, cost);
                apply(sym, real(sym, 0.0));
                BacktestResult rl = run(sym, spec, cost);
                System.out.printf("%-9s %-12s %10.2f %10.2f %10.2f %10.2f %10.2f%n",
                    sym, spec, rr == null ? Double.NaN : rr.profitFactor(), rr == null ? 0 : rr.totalPnl(),
                    rr == null ? 0 : rr.totalSwap(), rl == null ? Double.NaN : rl.profitFactor(),
                    rl == null ? 0 : rl.totalPnl());
                if (z != null && "2006-2015".equals(spec)) {
                    System.out.printf("%-9s %-12s prix: PF %.2f net %.2f%n", "", "", z.profitFactor(), z.totalPnl());
                }
            }
        }
        SwapCalculator.clearRateOverride();
    }

    // ---------------------------------------------------------------- 5. famille de taux

    static void family(BacktestExecutionCost cost) throws Exception {
        section("5) SENSIBILITÉ À LA FAMILLE DE TAUX (uniforme 3M vs policy 44e), 2006-2026, 0 spread");
        System.out.printf("%-9s %-22s %6s %7s %11s %10s%n", "PAIRE", "FAMILLE", "PF", "TRADES", "NET$", "SWAP$");
        for (String sym : new String[]{"GBP_JPY", "AUD_USD", "USD_CAD"}) {
            for (String fam : new String[]{"uniforme 3M", "policy (44e)"}) {
                double[] d = fam.startsWith("uniforme") ? DIFF_U.get(sym) : DIFF_P.get(sym);
                SwapCalculator.setYearlyRateOverride(sym, yearlyTable(d, 0.0));
                BacktestResult r = run(sym, YEAR_SPEC, cost);
                if (r == null) continue;
                System.out.printf("%-9s %-22s %6.2f %7d %11.2f %10.2f%n",
                    sym, fam, r.profitFactor(), r.totalTrades(), r.totalPnl(), r.totalSwap());
            }
        }
        SwapCalculator.clearRateOverride();
    }

    // ---------------------------------------------------------------- 6. overlay de taille

    /** Régimes du 43e : rang percentile CAUSAL de la vol 21 j (traîne 1000), mesuré la VEILLE. */
    static TreeMap<java.time.LocalDate, Double> eqRank;
    static final Map<String, TreeMap<java.time.LocalDate, Double>> ownRank = new LinkedHashMap<>();
    static final Map<String, TreeMap<java.time.LocalDate, Double>> rankAnd = new LinkedHashMap<>();

    static void buildRegimes() throws Exception {
        TreeMap<java.time.LocalDate, Double> mes = dailyClosesCsv("MES_D1.csv");
        eqRank = trailingRank(vol(rets(mes), 21), 1000);
        System.out.printf("[régime] MES vol21 rang causal : %s → %s (%d obs)%n",
            eqRank.isEmpty() ? "-" : eqRank.firstKey(), eqRank.isEmpty() ? "-" : eqRank.lastKey(), eqRank.size());
        for (String sym : new String[]{"GBP_JPY", "GBP_USD"}) {
            TreeMap<java.time.LocalDate, Double> d = dailyClosesBars(sym, 2006, 2026);
            TreeMap<java.time.LocalDate, Double> r = trailingRank(vol(rets(d), 21), 1000);
            ownRank.put(sym, r);
            TreeMap<java.time.LocalDate, Double> and = new TreeMap<>();
            for (var e : r.entrySet()) {
                Double q = regimeAt(eqRank, e.getKey());
                if (q != null) and.put(e.getKey(), Math.min(q, e.getValue()));
            }
            rankAnd.put(sym, and);
        }
    }

    static Double regimeAt(TreeMap<java.time.LocalDate, Double> rank, java.time.LocalDate d) {
        var e = rank.floorEntry(d.minusDays(1));
        return e == null ? null : e.getValue();
    }

    static TreeMap<java.time.LocalDate, Double> dailyClosesCsv(String csv) throws Exception {
        TreeMap<java.time.LocalDate, Double> map = new TreeMap<>();
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Path.of("data/historical/futures").resolve(csv))) {
            String[] f = line.split(",");
            if (f.length < 5 || f[0].startsWith("Date")) continue;
            try { map.put(java.time.LocalDate.parse(f[0].trim()), Double.parseDouble(f[4])); } catch (Exception ignore) {}
        }
        return map;
    }

    static TreeMap<java.time.LocalDate, Double> dailyClosesBars(String symbol, int from, int to) throws Exception {
        TreeMap<java.time.LocalDate, Double> map = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, java.nio.file.Paths.get("data/historical/bars"));
                if (bars == null) continue;
                for (Bar b : bars) map.put(b.timestamp().atZone(java.time.ZoneId.of("UTC")).toLocalDate(), b.close());
            } catch (Exception ignored) { }
        }
        return map;
    }

    static TreeMap<java.time.LocalDate, Double> rets(TreeMap<java.time.LocalDate, Double> m) {
        TreeMap<java.time.LocalDate, Double> out = new TreeMap<>();
        java.time.LocalDate prev = null;
        for (var e : m.entrySet()) {
            if (prev != null && m.get(prev) > 0) out.put(e.getKey(), (e.getValue() - m.get(prev)) / m.get(prev));
            prev = e.getKey();
        }
        return out;
    }

    static TreeMap<java.time.LocalDate, Double> vol(TreeMap<java.time.LocalDate, Double> r, int win) {
        TreeMap<java.time.LocalDate, Double> out = new TreeMap<>();
        List<Double> buf = new ArrayList<>();
        for (var e : r.entrySet()) {
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

    static TreeMap<java.time.LocalDate, Double> trailingRank(TreeMap<java.time.LocalDate, Double> s, int trail) {
        TreeMap<java.time.LocalDate, Double> out = new TreeMap<>();
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

    /** Part des vendredis en régime HAUT (AND ≥ seuil), causale. */
    static double highShare(String sym, double threshold) {
        TreeMap<java.time.LocalDate, Double> ranks = rankAnd.get(sym);
        List<Bar> bars = barsCache.get(sym + "|" + YEAR_SPEC);
        if (bars == null) {
            try {
                bars = HistoricalDataLoader.loadFromArgs(sym, sym, YEAR_SPEC).bars();
                barsCache.put(sym + "|" + YEAR_SPEC, bars);
            } catch (Exception e) { return 0; }
        }
        Set<java.time.LocalDate> fri = new TreeSet<>();
        for (Bar b : bars) {
            var d = b.timestamp().atZone(java.time.ZoneId.of("UTC")).toLocalDate();
            if (d.getDayOfWeek() == java.time.DayOfWeek.FRIDAY) fri.add(d);
        }
        int n = 0, hi = 0;
        for (var d : fri) {
            Double r = regimeAt(ranks, d);
            if (r == null) continue;
            n++;
            if (r >= threshold) hi++;
        }
        return n == 0 ? 0 : hi / (double) n;
    }

    static BacktestResult runOverlay(String symbol, String yearSpec, boolean overlay,
                                     double qty, BacktestExecutionCost cost) throws Exception {
        String key = symbol + "|" + yearSpec;
        List<Bar> bars = barsCache.get(key);
        if (bars == null) {
            bars = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec).bars();
            barsCache.put(key, bars);
        }
        if (bars == null || bars.isEmpty()) return null;
        var strategy = new GoldWeekdayEffectStrategy("FxWeekdaySession", symbol, FRI, Order.Side.SELL)
            .withQuantity(qty).withRegime(rankAnd.get(symbol));
        if (overlay) strategy.withOverlay(1.0, 2.0, 60.0);
        return RunContext.forStrategy(null, "FxWeekdaySession", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    static void overlay(BacktestExecutionCost cost) throws Exception {
        section("6) RE-BASELINE DE LA CONFIG RECOMMANDÉE DU 43e (OVERLAY de vol 2u/1u) SOUS LE CARRY RÉEL");
        buildRegimes();
        for (String sym : new String[]{"GBP_JPY", "GBP_USD"}) {
            double u = 1 + highShare(sym, 60.0);
            System.out.printf("%n--- %s — part de vendredis en régime HAUT (AND ≥ 60) = %.1f%% ⇒ FLAT éq. = %.3fu%n",
                sym, (u - 1) * 100, u);
            System.out.printf("%-14s %-12s %-18s %6s %8s %11s %10s %12s%n",
                "HYPOTHÈSE", "PÉRIODE", "VARIANTE", "PF", "TRADES", "NET$", "NET$/u", "vs FLAT");
            for (Hyp h : new Hyp[]{ZERO, REPO, real(sym, 0.0), real(sym, HALF_SPREAD)}) {
                apply(sym, h);
                for (String spec : new String[]{"2006-2026", "2006-2015", "2016-2026"}) {
                    var base = runOverlay(sym, spec, false, QTY, cost);
                    var ov = runOverlay(sym, spec, true, QTY, cost);
                    var flat = runOverlay(sym, spec, false, QTY * u, cost);
                    if (base == null || ov == null || flat == null) continue;
                    double ovU = ov.totalPnl() / u, flatU = flat.totalPnl() / u;
                    System.out.printf("%-14s %-12s %-18s %6.2f %8d %11.2f %10.2f %11s%n",
                        h.label(), spec, "baseline 1u", base.profitFactor(), base.totalTrades(), base.totalPnl(),
                        base.totalPnl(), "-");
                    System.out.printf("%-14s %-12s %-18s %6.2f %8d %11.2f %10.2f %11s%n",
                        "", "", "OVERLAY 2u/1u", ov.profitFactor(), ov.totalTrades(), ov.totalPnl(), ovU, "-");
                    System.out.printf("%-14s %-12s %-18s %6.2f %8d %11.2f %10.2f %+10.1f%%%n",
                        "", "", String.format("FLAT %.3fu (éq.)", u), flat.profitFactor(), flat.totalTrades(),
                        flat.totalPnl(), flatU, 100 * (ovU - flatU) / Math.abs(flatU));
                }
                System.out.println();
            }
        }
        SwapCalculator.clearRateOverride();
    }

    // ---------------------------------------------------------------- helpers

    static final Map<String, List<Bar>> barsCache = new HashMap<>();

    static BacktestResult run(String symbol, String yearSpec, BacktestExecutionCost cost) throws Exception {
        String key = symbol + "|" + yearSpec;
        List<Bar> bars = barsCache.get(key);
        if (bars == null) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
            bars = loaded.bars();
            barsCache.put(key, bars);
        }
        if (bars == null || bars.isEmpty()) return null;
        var strategy = new GoldWeekdayEffectStrategy("FxWeekdaySession", symbol, FRI, Order.Side.SELL).withQuantity(QTY);
        return RunContext.forStrategy(null, "FxWeekdaySession", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    static void section(String s) {
        System.out.println("\n==============================================================");
        System.out.println(s);
        System.out.println("==============================================================");
    }
}
