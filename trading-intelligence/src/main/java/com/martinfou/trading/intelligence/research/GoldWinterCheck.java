package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldWinterCheck — Vérifie la robustesse du cluster hivernal XAU/USD
 * (Décembre-Janvier) ressorti du scan mensuel SeasonalityAnalyzer
 * (Dec +13.09% avg 70.6% hit p=0.0092 ✅✅ ; Jan +3.47% 70.6% p=0.0017 ✅✅).
 *
 * Le chiffre December +13% est suspect (avg == median == Q4, p identique :
 * l'agrégation QUARTER de l'analyzer semble buggée). Vérification année par
 * année + split IS/OOS + contrôle mois voisins + régime (méthode du 5 août
 * qui a tué XAU August : artefact de tendance longue si les voisins ont le
 * même hit rate).
 */
public class GoldWinterCheck {
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "XAU_USD";
        int fromYear = 2006, toYear = 2026;
        var barsDir = Paths.get(BARS_DIR);

        System.out.println("=== " + symbol + " — scan mensuel année par année (2006-2026) ===");
        Map<Integer, List<Bar>> byYear = new TreeMap<>();
        for (int y = fromYear; y <= toYear; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) byYear.put(y, bars);
            } catch (Exception e) { /* skip */ }
        }

        // --- Table 12 mois par année (repère brute, contrôles voisins) ---
        Map<Integer, List<Double>> byMonth = new TreeMap<>();
        for (var e : byYear.entrySet()) {
            int y = e.getKey();
            if (y == 2008 || y == 2020 || y == 2022) continue; // crise (règle analyzer)
            for (int m = 1; m <= 12; m++) {
                double r = windowReturn(e.getValue(), m, 1, m, 31);
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

        // --- Fenêtres hivernales candidates ---
        String[][] windows = {
            {"Nov 1-30",  "11","1","11","30"},
            {"Dec 1-31",  "12","1","12","31"},
            {"Jan 1-31",   "1","1", "1","31"},
            {"Feb 1-28",   "2","1", "2","28"},
            {"Jul 1-31",   "7","1", "7","31"},
            {"Aug 1-31",   "8","1", "8","31"},
            {"Oct 1-31",  "10","1","10","31"},
            {"Dec15-Jan15","12","15","1","15"},
            {"Dec1-Jan31", "12","1", "1","31"},
            {"Nov15-Jan31","11","15","1","31"},
        };
        System.out.println("\n=== Fenêtres hivernales " + symbol + " — split IS/OOS + régimes ===");
        for (String[] w : windows) {
            String label = w[0];
            int sm = Integer.parseInt(w[1]), sd = Integer.parseInt(w[2]);
            int em = Integer.parseInt(w[3]), ed = Integer.parseInt(w[4]);
            List<Double> all = new ArrayList<>(), is = new ArrayList<>(), oos = new ArrayList<>();
            List<Double> bull = new ArrayList<>(), bear = new ArrayList<>(), bull2 = new ArrayList<>();
            for (var e : byYear.entrySet()) {
                int y = e.getKey();
                double r = windowReturnCrossYear(byYear, y, sm, sd, em, ed);
                if (Double.isNaN(r)) continue;
                all.add(r);
                if (y <= 2015) is.add(r); else oos.add(r);
                if (y <= 2012) bull.add(r); else if (y <= 2015) bear.add(r); else bull2.add(r);
            }
            System.out.printf("%-14s ALL %+7.2f%% hit %3.0f%% | IS(06-15) %+7.2f%% hit %3.0f%% | OOS(16-26) %+7.2f%% hit %3.0f%% | bull06-12 %+6.2f%% | bear13-15 %+6.2f%% | bull2 16-25 %+6.2f%%%n",
                label,
                mean(all) * 100, hitRate(all) * 100,
                mean(is) * 100, hitRate(is) * 100,
                mean(oos) * 100, hitRate(oos) * 100,
                mean(bull) * 100, mean(bear) * 100, mean(bull2) * 100);
        }

        // --- Détail année par année de Dec + Jan (pour voir l'artefact) ---
        System.out.println("\n=== Détail année par année : DEC (1-31) et JAN (1-31) ===");
        for (var e : byYear.entrySet()) {
            int y = e.getKey();
            double dec = windowReturn(e.getValue(), 12, 1, 12, 31);
            double jan = windowReturn(e.getValue(), 1, 1, 1, 31);
            String crisis = (y == 2008 || y == 2020 || y == 2022) ? "  [crise]" : "";
            System.out.printf("%d: Dec %+7.2f%%  | Jan %+7.2f%%%s%n", y,
                Double.isNaN(dec) ? 0 : dec * 100,
                Double.isNaN(jan) ? 0 : jan * 100, crisis);
        }
    }

    /** Rendement d'une fenêtre qui peut chevaucher le 1er janvier (year y → y+1). */
    private static double windowReturnCrossYear(Map<Integer, List<Bar>> byYear, int y,
            int sm, int sd, int em, int ed) {
        List<Bar> startYear = byYear.get(y);
        List<Bar> endYear = (em == 1 && sm > em) ? byYear.get(y + 1) : byYear.get(y);
        if (startYear == null || endYear == null) return Double.NaN;
        // Start dans year y, end dans year y (ou y+1 si la fenêtre passe l'an)
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
