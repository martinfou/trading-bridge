package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldAutumnCheck — Mercredi 9 septembre 2026 (pattern saisonnier, 31e résultat).
 *
 * Question : le January effect or validé le 2 sept (BUY XAU Jan 1-31, PF 5.09,
 * IS/OOS 70/70, bear 13-15 positif) est-il le pic d'un cluster saisonnier plus
 * large qui s'étend en AUTOMNE (Sep/Oct/Nov — la saison de demande physique
 * indienne : Navratri/Diwali oct-nov, restock joailliers pré-fêtes) ou un
 * phénomène ISOLÉ de janvier (nouvel an chinois, rééquilibrage) ?
 *
 * Preuve déjà connue (contrôles du 2 sept, non passés au gate Pattern D complet) :
 *   - Oct 1-31 (contrôle GoldWinterCheck) : ALL +0.88% hit 60% | IS +0.19% 60%
 *     | OOS +1.56% 60% | bull +0.41% | bear -0.32% | bull2 +1.56% → signature
 *     late-bloomer suspecte (IS faible, OOS fort = régime bull2, pas saison).
 *   - Nov 1-30 (contrôle backtest) : PF 1.45, WR 50%, DD 8.98%, net +$2.5K —
 *     positif full-sample mais WR pile = moyenne de gains, pas de gate IS/OOS.
 *   - Sep : JAMAIS isolé sur XAU.
 *
 * Gate Pattern D (méthode du 5 août, appliquée par GoldWinterCheck) :
 *   1) hit rate IS 2006-15 → OOS 2016-25 STABLE (pas de chute > 20pp)
 *   2) positif en bear 2013-15 OU structure distincte des voisins (un edge
 *      saisonnier n'est pas une tendance longue : Aug a échoué 70→50)
 *   3) mois voisins distincts (Jan validé : voisins Feb OOS 40%, Jul bear nég)
 */
public class GoldAutumnCheck {
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "XAU_USD";
        int fromYear = 2006, toYear = 2026;
        var barsDir = Paths.get(BARS_DIR);

        System.out.println("=== " + symbol + " — scan mensuel année par année (2006-2025) ===");
        Map<Integer, List<Bar>> byYear = new TreeMap<>();
        for (int y = fromYear; y <= toYear; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) byYear.put(y, bars);
            } catch (Exception e) { /* skip */ }
        }
        System.out.println("Années chargées : " + byYear.keySet());

        // --- Table mensuelle (crise exclue, règle analyzer) : AUG, SEP, OCT, NOV, DEC, JAN, FEB ---
        Map<Integer, List<Double>> byMonth = new TreeMap<>();
        for (var e : byYear.entrySet()) {
            int y = e.getKey();
            if (y == 2008 || y == 2020 || y == 2022) continue; // crise (règle analyzer)
            for (int m : new int[]{7, 8, 9, 10, 11, 12, 1, 2}) {
                double r = windowReturn(e.getValue(), m, 1, m, lastDay(m));
                if (!Double.isNaN(r)) byMonth.computeIfAbsent(m, k -> new ArrayList<>()).add(r);
            }
        }
        System.out.println("Mois (années crise exclues) :");
        for (int m = 1; m <= 12; m++) {
            List<Double> rs = byMonth.get(m);
            if (rs == null || rs.isEmpty()) continue;
            System.out.printf("  %-9s avg %+7.2f%%  median %+7.2f%%  hit %4.0f%%  n=%d%n",
                Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                mean(rs) * 100, median(rs) * 100, hitRate(rs) * 100, rs.size());
        }

        // --- Fenêtres automne + contrôles connus (reproduction de signatures) ---
        String[][] windows = {
            // {label, sm, sd, em, ed, crossYear(0/1)}
            {"Sep 1-30",     "9","1","9","30",  "0"},
            {"Oct 1-31",     "10","1","10","31", "0"},
            {"Nov 1-30",     "11","1","11","30", "0"},
            {"Sep15-Oct31",  "9","15","10","31", "0"},
            {"Oct1-Nov30",   "10","1","11","30", "0"},
            {"Sep15-Nov30",  "9","15","11","30", "0"},
            {"Oct15-Nov30",  "10","15","11","30", "0"},
            {"Nov1-Dec15",   "11","1","12","15", "0"},
            {"Nov15-Jan31",  "11","15","1","31", "1"},
            {"Nov1-Jan31",   "11","1","1","31",  "1"},
            // Contrôles signatures connues
            {"Jan 1-31 [validé]", "1","1","1","31","0"},
            {"Aug 1-31 [artefact]","8","1","8","31","0"},
            {"Feb 1-28 [contrôle]","2","1","2","28","0"},
            {"Jul 1-31 [contrôle]","7","1","7","31","0"},
        };
        System.out.println("\n=== Fenêtres automne XAU — split IS/OOS + régimes (Pattern D) ===");
        for (String[] w : windows) {
            String label = w[0];
            int sm = Integer.parseInt(w[1]), sd = Integer.parseInt(w[2]);
            int em = Integer.parseInt(w[3]), ed = Integer.parseInt(w[4]);
            boolean cross = w[5].equals("1");
            List<Double> all = new ArrayList<>(), is = new ArrayList<>(), oos = new ArrayList<>();
            List<Double> bull = new ArrayList<>(), bear = new ArrayList<>(), bull2 = new ArrayList<>();
            for (var e : byYear.entrySet()) {
                int y = e.getKey();
                double r = cross ? windowReturnCrossYear(byYear, y, sm, sd, em, ed)
                                 : windowReturnYear(byYear, y, sm, sd, em, ed);
                if (Double.isNaN(r)) continue;
                all.add(r);
                if (y <= 2015) is.add(r); else oos.add(r);
                if (y <= 2012) bull.add(r); else if (y <= 2015) bear.add(r); else bull2.add(r);
            }
            System.out.printf("%-22s ALL %+7.2f%% hit %3.0f%% | IS(06-15) %+7.2f%% hit %3.0f%% | OOS(16-25) %+7.2f%% hit %3.0f%% | bull06-12 %+6.2f%% | bear13-15 %+6.2f%% | bull2 16-25 %+6.2f%%%n",
                label,
                mean(all) * 100, hitRate(all) * 100,
                mean(is) * 100, hitRate(is) * 100,
                mean(oos) * 100, hitRate(oos) * 100,
                mean(bull) * 100, mean(bear) * 100, mean(bull2) * 100);
        }

        // --- Détail année par année : SEP, OCT, NOV (lire la structure) ---
        System.out.println("\n=== Détail année par année : SEP / OCT / NOV ===");
        for (var e : byYear.entrySet()) {
            int y = e.getKey();
            double sep = windowReturn(e.getValue(), 9, 1, 9, 30);
            double oct = windowReturn(e.getValue(), 10, 1, 10, 31);
            double nov = windowReturn(e.getValue(), 11, 1, 11, 30);
            String crisis = (y == 2008 || y == 2020 || y == 2022) ? "  [crise]" : "";
            System.out.printf("%d: Sep %+7.2f%%  | Oct %+7.2f%%  | Nov %+7.2f%%%s%n", y,
                Double.isNaN(sep) ? 0 : sep * 100,
                Double.isNaN(oct) ? 0 : oct * 100,
                Double.isNaN(nov) ? 0 : nov * 100, crisis);
        }
    }

    private static int lastDay(int m) {
        return (m == 2) ? 28 : (m == 4 || m == 6 || m == 9 || m == 11) ? 30 : 31;
    }

    /** Fenêtre dans une même année (open 1er bar du jour S → close dernier bar du jour E). */
    private static double windowReturnYear(Map<Integer, List<Bar>> byYear, int y,
            int sm, int sd, int em, int ed) {
        List<Bar> bars = byYear.get(y);
        if (bars == null) return Double.NaN;
        return windowReturn(bars, sm, sd, em, ed);
    }

    /** Rendement d'une fenêtre qui peut chevaucher le 1er janvier (year y → y+1). */
    private static double windowReturnCrossYear(Map<Integer, List<Bar>> byYear, int y,
            int sm, int sd, int em, int ed) {
        List<Bar> startYear = byYear.get(y);
        List<Bar> endYear = byYear.get(y + 1);
        if (startYear == null || endYear == null) return Double.NaN;
        double start = Double.NaN, end = Double.NaN;
        for (Bar b : startYear) {
            ZonedDateTime z = b.timestamp().atZone(ZoneId.of("UTC"));
            if (z.getMonthValue() == sm && z.getDayOfMonth() == sd && Double.isNaN(start)) start = b.open();
        }
        for (Bar b : endYear) {
            ZonedDateTime z = b.timestamp().atZone(ZoneId.of("UTC"));
            if (z.getMonthValue() == em && z.getDayOfMonth() == ed) end = b.close();
        }
        if (Double.isNaN(start) || Double.isNaN(end) || start <= 0) return Double.NaN;
        return (end - start) / start;
    }

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
        long pos = v.stream().filter(d -> d > 0).count();
        return (double) pos / v.size();
    }
}
