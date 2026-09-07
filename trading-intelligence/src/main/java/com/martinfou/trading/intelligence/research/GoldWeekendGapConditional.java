package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldWeekendGapConditional — refine XAU weekend gap with conditionals:
 *   1. by calendar month (is the January gold effect a gap effect?)
 *   2. by prior-week direction (mean reversion / continuation at open?)
 *   3. December-January cluster specifically
 */
public class GoldWeekendGapConditional {
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        String sym = "XAU_USD";
        List<Bar> all = loadYears(sym, 2006, 2025);
        all.sort(Comparator.comparing(Bar::timestamp));
        System.out.println("=== XAU_USD — weekend GAP conditionals ===");

        // Build: for each weekend gap: date of last real Fri bar, gap open-based, prev-5-real-day return
        boolean[] real = new boolean[all.size()];
        real[0] = true;
        for (int i = 1; i < all.size(); i++)
            real[i] = Math.abs(all.get(i).close() - all.get(i - 1).close()) > 1e-12;

        // map real-bar index -> days back for prior 5-day return
        List<int[]> gaps = new ArrayList<>(); // {indexOfLastReal, indexOfFirstRealAfter}
        int i = 1;
        while (i < all.size()) {
            if (real[i]) { i++; continue; }
            int lastReal = i - 1;
            int k = i;
            while (k < all.size() && !real[k]) k++;
            if (k >= all.size()) break;
            long flatMs = all.get(k).timestamp().toEpochMilli() - all.get(lastReal).timestamp().toEpochMilli();
            if (flatMs >= 6 * 3600000L) gaps.add(new int[]{lastReal, k});
            i = k;
        }

        Map<Integer, List<Double>> byMonth = new TreeMap<>();
        List<Double> janGaps = new ArrayList<>(), decJanGaps = new ArrayList<>(), otherGaps = new ArrayList<>();
        List<Double> priorUp = new ArrayList<>(), priorDown = new ArrayList<>();
        // index of last real bar per calendar day to compute "5 real days back" => ~7 calendar days
        TreeMap<LocalDate, Integer> lastRealIdxByDay = new TreeMap<>();
        for (int idx = 0; idx < all.size(); idx++)
            if (real[idx]) lastRealIdxByDay.put(all.get(idx).timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), idx);

        for (int[] g : gaps) {
            int li = g[0], fi = g[1];
            ZonedDateTime z0 = all.get(li).timestamp().atZone(ZoneId.of("UTC"));
            int month = z0.getMonthValue();
            double c0 = all.get(li).close();
            double open1 = all.get(fi).open();
            double gap = (open1 - c0) / c0;
            byMonth.computeIfAbsent(month, m -> new ArrayList<>()).add(gap);
            if (month == 1) janGaps.add(gap);
            if (month == 12 || month == 1) decJanGaps.add(gap); else otherGaps.add(gap);
            // prior week: close 7 calendar days before the last real Fri
            LocalDate d0 = z0.toLocalDate();
            LocalDate d7 = d0.minusDays(8);
            Integer idx7 = lastRealIdxByDay.floorEntry(d7) == null ? null : lastRealIdxByDay.floorEntry(d7).getValue();
            if (idx7 != null && idx7 < li) {
                double priorRet = (c0 - all.get(idx7).close()) / all.get(idx7).close();
                if (priorRet >= 0) priorUp.add(gap); else priorDown.add(gap);
            }
        }
        System.out.println("Month  | n   | avg      hit");
        for (var e : byMonth.entrySet())
            System.out.printf("  %2d   | %3d | %+7.4f%%  %3.0f%%%n", e.getKey(), e.getValue().size(),
                mean(e.getValue()) * 100, hit(e.getValue()) * 100);
        System.out.printf("JAN    n=%d avg %+7.4f%% hit %.0f%%%n", janGaps.size(), mean(janGaps)*100, hit(janGaps)*100);
        System.out.printf("DEC+JAN n=%d avg %+7.4f%% hit %.0f%%%n", decJanGaps.size(), mean(decJanGaps)*100, hit(decJanGaps)*100);
        System.out.printf("OTHERS n=%d avg %+7.4f%% hit %.0f%%%n", otherGaps.size(), mean(otherGaps)*100, hit(otherGaps)*100);
        System.out.printf("Prior-week UP   n=%d avg %+7.4f%% hit %.0f%%%n", priorUp.size(), mean(priorUp)*100, hit(priorUp)*100);
        System.out.printf("Prior-week DOWN n=%d avg %+7.4f%% hit %.0f%%%n", priorDown.size(), mean(priorDown)*100, hit(priorDown)*100);
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
