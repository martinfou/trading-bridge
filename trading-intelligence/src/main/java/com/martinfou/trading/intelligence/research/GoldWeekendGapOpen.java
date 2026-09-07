package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldWeekendGapOpen — refine: measure the pure "gap" as close(last real Fri)
 * -> OPEN(first real post-weekend bar). Standard economic definition of a
 * market gap. Also measures Fri-close -> Mon-open (through the Sunday session)
 * and the XAU Friday day itself as sanity.
 */
public class GoldWeekendGapOpen {
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        for (String sym : new String[]{"XAU_USD", "EUR_USD", "GBP_USD"}) {
            List<Bar> all = loadYears(sym, 2006, 2025);
            all.sort(Comparator.comparing(Bar::timestamp));
            System.out.println("=== " + sym + " — GAP (close dernier réel ven → OPEN 1er réel post-week-end) ===");
            boolean[] real = new boolean[all.size()];
            real[0] = true;
            for (int i = 1; i < all.size(); i++)
                real[i] = Math.abs(all.get(i).close() - all.get(i - 1).close()) > 1e-12;

            List<Double> gapAll = new ArrayList<>(), gapIs = new ArrayList<>(), gapOos = new ArrayList<>();
            List<Double> friDayAll = new ArrayList<>();   // sanity: Thu close -> Fri close (day move)
            int i = 1;
            while (i < all.size()) {
                if (real[i]) { i++; continue; }
                int lastReal = i - 1;
                int k = i;
                while (k < all.size() && !real[k]) k++;
                if (k >= all.size()) break;
                long flatMs = all.get(k).timestamp().toEpochMilli() - all.get(lastReal).timestamp().toEpochMilli();
                if (flatMs >= 6 * 3600000L) {
                    double c0 = all.get(lastReal).close();
                    double open1 = all.get(k).open();   // pure gap: first real bar OPEN
                    double gap = (open1 - c0) / c0;
                    int y0 = all.get(lastReal).timestamp().atZone(ZoneId.of("UTC")).getYear();
                    gapAll.add(gap);
                    if (y0 <= 2015) gapIs.add(gap); else gapOos.add(gap);
                }
                i = k;
            }
            System.out.printf("GAP ALL  (n=%4d): avg %+6.4f%%  hit %4.0f%%%n", gapAll.size(), mean(gapAll) * 100, hit(gapAll) * 100);
            System.out.printf("GAP IS   (n=%4d): avg %+6.4f%%  hit %4.0f%%%n", gapIs.size(), mean(gapIs) * 100, hit(gapIs) * 100);
            System.out.printf("GAP OOS  (n=%4d): avg %+6.4f%%  hit %4.0f%%%n", gapOos.size(), mean(gapOos) * 100, hit(gapOos) * 100);

            // Deciles to check tails / asymmetry
            double[] arr = gapAll.stream().mapToDouble(d -> d).sorted().toArray();
            System.out.printf("  p10=%+.4f%% p25=%+.4f%% p50=%+.4f%% p75=%+.4f%% p90=%+.4f%%%n",
                arr[arr.length/10]*100, arr[arr.length/4]*100, arr[arr.length/2]*100,
                arr[3*arr.length/4]*100, arr[9*arr.length/10]*100);
            System.out.println();
        }
    }

    private static Map<Integer, List<Bar>> loadYearsMap(String symbol, int from, int to) throws Exception {
        var barsDir = Paths.get(BARS_DIR);
        Map<Integer, List<Bar>> byYear = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) byYear.put(y, bars);
            } catch (Exception e) { }
        }
        return byYear;
    }

    private static List<Bar> loadYears(String symbol, int from, int to) throws Exception {
        List<Bar> all = new ArrayList<>();
        for (var e : loadYearsMap(symbol, from, to).entrySet()) all.addAll(e.getValue());
        return all;
    }

    private static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }
    private static double hit(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().filter(d -> d > 0).count() / (double) v.size();
    }
}
