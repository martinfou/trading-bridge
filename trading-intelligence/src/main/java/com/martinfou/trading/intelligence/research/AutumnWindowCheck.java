package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * AutumnWindowCheck — Vérifie la robustesse des fenêtres saisonnières d'automne
 * (H2) avant qu'elles ne deviennent tradables :
 *   1. USD/JPY BUY Sep 27 → Nov 11 (88% hit rate, "fiscal half-end yen weakness")
 *   2. USD/CAD BUY Oct 12 → Nov 26 (94% hit rate, "end of driving season → oil ↓")
 *
 * Ces fenêtres viennent de l'analyse SeasonalityAnalyzer de JUILLET 2026
 * (pré-fix) et n'ont JAMAIS passé le gate anti-artefact du 5 août
 * (split IS/OOS + contrôle fenêtres voisines) qui a tué XAU August,
 * NOV (poison du 19 août) et October (poison du 21 août).
 *
 * Méthode (issue du XauAugustCheck / AugustRiskCheck) :
 *   1. Rendement de fenêtre année par année (2006-2026) + split IS/OOS
 *   2. Contrôle "même hit rate sur fenêtres voisines" (avant/après la fenêtre
 *      + mois adjacents) — signature d'une tendance longue vs un vrai edge
 *   3. Seul un hit rate de fenêtre DISTINCT des voisines + stable OOS
 *      (pas d'inversion) est exploitable.
 *
 * Critère de survie (Pattern D) :
 *   - IS hit ≥ 65% ET OOS hit ≥ 60% (pas d'effondrement)
 *   - Pas d'inversion de signe de avg entre IS et OOS
 *   - Fenêtre distincte des contrôles voisins (delta ≥ 15 points)
 */
public class AutumnWindowCheck {
    private static final String BARS_DIR = "data/historical/bars";
    private static final int[] CRISIS_YEARS = {2008, 2020, 2022};

    public static void main(String[] args) throws Exception {
        int fromYear = 2006, toYear = 2026;
        var barsDir = Paths.get(BARS_DIR);

        // Fenêtres cibles + contrôles voisins (avant / après)
        String[][] targets = {
            // {symbol, label, startM, startD, endM, endD}
            {"USD_JPY", "USDJPY BUY Sep27-Nov11", "9", "27", "11", "11"},
            {"USD_CAD", "USDCAD BUY Oct12-Nov26", "10", "12", "11", "26"},
        };
        int[][][] controls = {
            // USD_JPY : avant (Sep 1-26) / après (Nov 12-30) / Sep seul / Oct seul / Nov seul
            {{9,1,9,26},{11,12,11,30},{9,1,9,30},{10,1,10,31},{11,1,11,30}},
            // USD_CAD : avant (Oct 1-11) / après (Nov 27-Dec 15) / Oct seul / Nov seul / Dec seul
            {{10,1,10,11},{11,27,12,15},{10,1,10,31},{11,1,11,30},{12,1,12,31}},
        };

        System.out.println("================================================================");
        System.out.println("AUTUMN WINDOW CHECK — fenêtres saisonnières H2 (2006-2026)");
        System.out.println("Gate anti-artefact : IS/OOS + contrôle fenêtres voisines");
        System.out.println("================================================================");

        for (int t = 0; t < targets.length; t++) {
            String[] target = targets[t];
            String symbol = target[0];
            int sm = Integer.parseInt(target[2]), sd = Integer.parseInt(target[3]);
            int em = Integer.parseInt(target[4]), ed = Integer.parseInt(target[5]);

            Map<Integer, List<Bar>> byYear = loadYears(symbol, fromYear, toYear, barsDir);
            if (byYear.isEmpty()) {
                System.out.println(symbol + " : PAS DE DONNÉES");
                continue;
            }

            System.out.println("\n========== " + target[1] + " (" + symbol + ") ==========");
            List<Double> all = new ArrayList<>(), is = new ArrayList<>(), oos = new ArrayList<>();
            for (var e : byYear.entrySet()) {
                int y = e.getKey();
                List<Bar> bars = e.getValue();
                double ret = windowReturn(bars, sm, sd, em, ed);
                if (Double.isNaN(ret)) continue;
                all.add(ret);
                if (y <= 2015) is.add(ret); else oos.add(ret);
                String crisis = isCrisisYear(y) ? "  [crise — à surveiller]" : "";
                System.out.printf("%d: %+6.2f%%%s%n", y, ret * 100, crisis);
            }
            double allAvg = mean(all), isAvg = mean(is), oosAvg = mean(oos);
            double isHit = hitRate(is), oosHit = hitRate(oos);
            System.out.printf("ALL  (%d yrs): avg %+.2f%%  median %+.2f%%  hit %.0f%%%n",
                all.size(), allAvg * 100, median(all) * 100, hitRate(all) * 100);
            System.out.printf("IS   (%d yrs, 2006-2015): avg %+.2f%%  hit %.0f%%%n",
                is.size(), isAvg * 100, isHit * 100);
            System.out.printf("OOS  (%d yrs, 2016-2026): avg %+.2f%%  hit %.0f%%%n",
                oos.size(), oosAvg * 100, oosHit * 100);

            // Poison signature (NOV 19 août / October 21 août) = fort en IS, inversé en OOS
            boolean poison = isAvg > 0.002 && oosAvg < -0.002;
            // Late-bloomer = absent en IS (avg négatif), fort en OOS → régime récent, pas saisonnalité
            boolean lateBloom = isAvg < -0.001 && oosAvg > 0.002;
            boolean collapsed = isHit >= 0.65 && oosHit < 0.50;

            // --- Contrôles voisins ---
            System.out.println("--- Contrôles voisins (IS/OOS split) ---");
            double[] ctrlHits = new double[controls[t].length];
            for (int c = 0; c < controls[t].length; c++) {
                int[] w = controls[t][c];
                List<Double> cIs = new ArrayList<>(), cOos = new ArrayList<>();
                for (var e : byYear.entrySet()) {
                    int y = e.getKey();
                    if (isCrisisYear(y)) continue;  // même exclusion que le check août
                    double r = windowReturn(e.getValue(), w[0], w[1], w[2], w[3]);
                    if (Double.isNaN(r)) continue;
                    if (y <= 2015) cIs.add(r); else cOos.add(r);
                }
                ctrlHits[c] = hitRate(cOos);
                System.out.printf("  Ctrl %02d/%02d-%02d/%02d : IS hit %4.0f%% (avg %+.2f%%) | OOS hit %4.0f%% (avg %+.2f%%)  n=%d/%d%n",
                    w[0], w[1], w[2], w[3],
                    hitRate(cIs) * 100, mean(cIs) * 100,
                    hitRate(cOos) * 100, mean(cOos) * 100, cIs.size(), cOos.size());
            }
            double maxCtrl = Arrays.stream(ctrlHits).max().orElse(0);

            // --- Verdict préliminaire ---
            System.out.println("--- Verdict préliminaire ---");
            System.out.printf("  OOS hit cible: %.0f%%  |  meilleur contrôle OOS: %.0f%%%n", oosHit * 100, maxCtrl * 100);
            if (poison) System.out.println("  ❌ INVERSION IS→OOS (signature NOV/October — fort IS, négatif OOS) — REJECT");
            else if (lateBloom) System.out.println("  ❌ EDGE ABSENT EN IS (avg IS < 0), fort seulement en OOS = RÉGIME RÉCENT, pas saisonnalité — REJECT");
            else if (collapsed) System.out.println("  ❌ EFFONDREMENT OOS (IS ≥65% → OOS <50%) — REJECT");
            else if (oosHit >= 0.60 && oosHit - maxCtrl >= 0.15)
                System.out.println("  ✅ SURVIT : OOS stable + distinct des voisins → backtest autorisé");
            else if (oosHit >= 0.55)
                System.out.println("  ⚠️ MARGINAL : OOS faible ou peu distinct — backtest en avertissement");
            else
                System.out.println("  ❌ OOS < 55% — pas d'edge OOS — REJECT");
            System.out.println();
        }
        System.out.println("DONE");
    }

    private static Map<Integer, List<Bar>> loadYears(String symbol, int from, int to, Path barsDir) {
        Map<Integer, List<Bar>> byYear = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) byYear.put(y, bars);
            } catch (Exception e) { /* skip */ }
        }
        return byYear;
    }

    private static boolean isCrisisYear(int y) {
        for (int c : CRISIS_YEARS) if (c == y) return true;
        return false;
    }

    /** Rendement open(start) → close(end), NaN si une borne manque. */
    private static double windowReturn(List<Bar> bars, int sm, int sd, int em, int ed) {
        double start = Double.NaN, end = Double.NaN;
        for (Bar b : bars) {
            ZonedDateTime z = b.timestamp().atZone(ZoneId.of("UTC"));
            int m = z.getMonthValue(), d = z.getDayOfMonth();
            if (m == sm && d == sd && Double.isNaN(start)) start = b.open();
            if (m == em && d == ed) end = b.close();
        }
        if (Double.isNaN(start) || Double.isNaN(end) || start <= 0) return Double.NaN;
        return (end - start) / start;
    }

    private static double mean(List<Double> v) {
        if (v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 0 ? (s.get(n/2 - 1) + s.get(n/2)) / 2.0 : s.get(n/2);
    }

    private static double hitRate(List<Double> v) {
        if (v.isEmpty()) return 0;
        return v.stream().filter(d -> d > 0).count() / (double) v.size();
    }
}
