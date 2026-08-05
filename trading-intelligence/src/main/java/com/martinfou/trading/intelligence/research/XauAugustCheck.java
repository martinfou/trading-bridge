package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * XauAugustCheck — Vérifie la robustesse du pattern XAU/USD August.
 * Affiche le rendement d'août année par année (2006-2026) + sous-périodes
 * (IS 2006-2015 vs OOS 2016-2026) pour détecter un artefact de tendance.
 */
public class XauAugustCheck {
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        String symbol = "XAU_USD";
        int fromYear = 2006, toYear = 2026;
        var barsDir = Paths.get(BARS_DIR);

        System.out.println("=== XAU/USD August returns by year (H1) ===");
        Map<Integer, List<Bar>> byYear = new TreeMap<>();
        for (int y = fromYear; y <= toYear; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) byYear.put(y, bars);
            } catch (Exception e) { /* skip */ }
        }

        List<Double> allAug = new ArrayList<>();
        List<Double> isAug = new ArrayList<>();   // 2006-2015
        List<Double> oosAug = new ArrayList<>();  // 2016-2026
        for (var e : byYear.entrySet()) {
            int y = e.getKey();
            List<Bar> bars = e.getValue();
            double ret = windowReturn(bars, 8, 1, 8, 31);
            if (Double.isNaN(ret)) continue;
            allAug.add(ret);
            if (y <= 2015) isAug.add(ret); else oosAug.add(ret);
            System.out.printf("%d: %+6.2f%%%s%n", y, ret * 100,
                (y == 2008 || y == 2020 || y == 2022) ? "  [outlier exclu]" : "");
        }
        System.out.println();
        System.out.printf("ALL  (%d yrs): avg %+.2f%%  median %+.2f%%  hit %.0f%%%n",
            allAug.size(), mean(allAug) * 100, median(allAug) * 100, hitRate(allAug) * 100);
        System.out.printf("IS   (%d yrs, 2006-2015): avg %+.2f%%  hit %.0f%%%n",
            isAug.size(), mean(isAug) * 100, hitRate(isAug) * 100);
        System.out.printf("OOS  (%d yrs, 2016-2026): avg %+.2f%%  hit %.0f%%%n",
            oosAug.size(), mean(oosAug) * 100, hitRate(oosAug) * 100);

        // Comparaison : rendement août vs moyenne des autres mois (contrôle tendance)
        System.out.println();
        System.out.println("=== Contrôle : August vs autres mois (même paire) ===");
        Map<Integer, List<Double>> byMonth = new TreeMap<>();
        for (var e : byYear.entrySet()) {
            int y = e.getKey();
            if (y == 2008 || y == 2020 || y == 2022) continue;
            List<Bar> bars = e.getValue();
            for (int m = 1; m <= 12; m++) {
                double r = windowReturn(bars, m, 1, m, 31);
                if (!Double.isNaN(r)) byMonth.computeIfAbsent(m, k -> new ArrayList<>()).add(r);
            }
        }
        for (int m = 1; m <= 12; m++) {
            List<Double> rs = byMonth.get(m);
            if (rs == null || rs.isEmpty()) continue;
            String name = Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
            System.out.printf("%-4s avg %+6.2f%%  median %+6.2f%%  hit %4.0f%%  n=%d%n",
                name, mean(rs) * 100, median(rs) * 100, hitRate(rs) * 100, rs.size());
        }
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
        return v.stream().filter(d -> d > 0).count() / (double) v.size();
    }
}
