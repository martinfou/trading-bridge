package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * AugustWindowScan — Scanne TOUTES les fenêtres glissantes et trie par hit rate ASC
 * (les fenêtres les plus BAISSIÈRES d'abord) pour valider le "August curse"
 * comme PLATEAU (plusieurs fenêtres voisines baissières), pas un pic isolé.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.intelligence.research.AugustWindowScan AUD_USD 31
 */
public class AugustWindowScan {

    private static final Set<Integer> OUTLIER_YEARS = Set.of(2008, 2020, 2022);
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "AUD_USD";
        int windowDays = args.length > 1 ? Integer.parseInt(args[1]) : 31;
        int fromYear = 2006, toYear = 2026;

        Map<Integer, List<Bar>> yearBars = loadYears(symbol, fromYear, toYear);

        // Daily returns per year
        Map<Integer, Map<Integer, Double>> dailyReturns = new TreeMap<>();
        for (var entry : yearBars.entrySet()) {
            int year = entry.getKey();
            if (OUTLIER_YEARS.contains(year)) continue;
            Map<Integer, Double> dayRets = new TreeMap<>();
            Map<Integer, List<Bar>> byDay = new TreeMap<>();
            for (Bar bar : entry.getValue()) {
                int doy = bar.timestamp().atZone(ZoneId.of("UTC")).getDayOfYear();
                byDay.computeIfAbsent(doy, k -> new ArrayList<>()).add(bar);
            }
            for (var d : byDay.entrySet()) {
                List<Bar> dayBars = d.getValue();
                if (dayBars.size() < 2) continue;
                double open = dayBars.get(0).open();
                double close = dayBars.get(dayBars.size() - 1).close();
                if (open > 0) dayRets.put(d.getKey(), (close - open) / open * 100.0);
            }
            dailyReturns.put(year, dayRets);
        }

        // Toutes les fenêtres
        List<Window> all = new ArrayList<>();
        int daysInYear = 365;
        for (int startDoy = 1; startDoy <= daysInYear - windowDays; startDoy += 3) {
            int endDoy = startDoy + windowDays;
            if (endDoy > daysInYear) break;

            List<Double> windowRets = new ArrayList<>();
            int yearsWithData = 0, yearsPositive = 0;
            for (var entry : dailyReturns.entrySet()) {
                Map<Integer, Double> dReturns = entry.getValue();
                double sum = 0;
                boolean hasData = false;
                for (int d = startDoy; d < endDoy; d++) {
                    if (dReturns.containsKey(d)) { sum += dReturns.get(d); hasData = true; }
                }
                if (hasData) {
                    windowRets.add(sum);
                    yearsWithData++;
                    if (sum > 0) yearsPositive++;
                }
            }
            if (yearsWithData < 10) continue;

            double avgRet = windowRets.stream().mapToDouble(d -> d).average().orElse(0);
            double medianRet = median(windowRets);
            double stdDev = stdDev(windowRets, avgRet);
            double hitRate = (double) yearsPositive / yearsWithData;
            double sharpe = stdDev > 0 ? avgRet / stdDev : 0;

            LocalDate startDate = LocalDate.ofYearDay(2000, startDoy);
            LocalDate endDate = LocalDate.ofYearDay(2000, endDoy);
            String label = startDate.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
                + " " + startDate.getDayOfMonth() + " - "
                + endDate.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
                + " " + endDate.getDayOfMonth();

            all.add(new Window(label, startDate.getMonthValue(), endDate.getMonthValue(),
                avgRet, medianRet, hitRate, sharpe, yearsWithData, yearsPositive));
        }

        // Trier par hit rate ASC (les plus baissières d'abord)
        all.sort(Comparator.comparingDouble(w -> w.hitRate));

        System.out.println("=== " + symbol + " — fenêtres " + windowDays + "j les plus BAISSIÈRES (2006-2026, outliers exclus) ===");
        System.out.printf("%-22s | %8s | %8s | %6s | %6s | %5s%n",
            "Fenêtre", "Avg%", "Med%", "Hit%", "Sharpe", "n");
        System.out.println("-".repeat(70));
        for (int i = 0; i < Math.min(25, all.size()); i++) {
            Window w = all.get(i);
            boolean touchesAugust = w.startMonth == 8 || w.endMonth == 8;
            String marker = touchesAugust ? " ◄ AUG" : "";
            System.out.printf("%-22s | %+7.2f | %+7.2f | %5.0f%% | %+6.2f | %5d%s%n",
                w.label, w.avgRet, w.medianRet, w.hitRate * 100, w.sharpe, w.n, marker);
        }
    }

    record Window(String label, int startMonth, int endMonth,
                  double avgRet, double medianRet, double hitRate, double sharpe, int n, int pos) {}

    private static Map<Integer, List<Bar>> loadYears(String symbol, int fromYear, int toYear) throws Exception {
        Map<Integer, List<Bar>> result = new TreeMap<>();
        var barsDir = Paths.get(BARS_DIR);
        for (int year = fromYear; year <= toYear; year++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, year, barsDir);
                if (bars != null && !bars.isEmpty()) result.put(year, bars);
            } catch (Exception e) { /* skip */ }
        }
        return result;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 0 ? (sorted.get(n/2 - 1) + sorted.get(n/2)) / 2.0 : sorted.get(n/2);
    }

    private static double stdDev(List<Double> values, double mean) {
        if (values.size() < 2) return 0;
        double sum = 0;
        for (double v : values) sum += (v - mean) * (v - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }
}
