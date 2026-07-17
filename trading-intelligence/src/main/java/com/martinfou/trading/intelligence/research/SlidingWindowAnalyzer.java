package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/**
 * SlidingWindowAnalyzer — Détecte les meilleures fenêtres calendaires glissantes.
 *
 * Au lieu de mois fixes (janvier, février...), cherche la meilleure fenêtre
 * de N jours qui maximise le hit rate sur 21 ans.
 *
 * Exemple : au lieu de "May", trouve "May 15 - Jun 15" (driving season exacte)
 * ou "Dec 20 - Jan 10" (holiday season).
 */
public class SlidingWindowAnalyzer {

    public record WindowResult(
        int startMonth, int startDay,
        int endMonth, int endDay,
        String label,
        double avgReturn,
        double medianReturn,
        double hitRate,
        double sharpe,
        int yearsObserved,
        int yearsPositive
    ) {}

    /**
     * Trouve la meilleure fenêtre glissante de N jours pour une paire.
     *
     * @param symbol       e.g. "USDCAD"
     * @param windowDays   taille de la fenêtre en jours (ex: 30, 45, 60)
     * @param fromYear     start year
     * @param toYear       end year
     * @param excludeOutliers exclude 2008/2020/2022
     * @return Top 10 fenêtres triées par hit rate
     */
    public static List<WindowResult> findBestWindows(
            String symbol, int windowDays,
            int fromYear, int toYear, boolean excludeOutliers) throws Exception {

        Map<Integer, List<Bar>> yearBars = loadYears(symbol, fromYear, toYear);

        // Build daily returns for each year
        Map<Integer, Map<Integer, Double>> dailyReturns = new TreeMap<>();

        for (var entry : yearBars.entrySet()) {
            int year = entry.getKey();
            List<Bar> bars = entry.getValue();
            if (excludeOutliers && OUTLIER_YEARS.contains(year)) continue;

            // Group bars by day of year
            Map<Integer, List<Bar>> byDay = new TreeMap<>();
            for (Bar bar : bars) {
                int doy = bar.timestamp().atZone(ZoneId.of("America/New_York")).getDayOfYear();
                byDay.computeIfAbsent(doy, k -> new ArrayList<>()).add(bar);
            }

            // Compute daily return (close of last bar / open of first bar)
            Map<Integer, Double> dayReturns = new TreeMap<>();
            for (var d : byDay.entrySet()) {
                List<Bar> dayBars = d.getValue();
                if (dayBars.size() < 2) continue;
                double open = dayBars.get(0).open();
                double close = dayBars.get(dayBars.size() - 1).close();
                if (open > 0) dayReturns.put(d.getKey(), (close - open) / open * 100.0);
            }
            dailyReturns.put(year, dayReturns);
        }

        // Test all possible windows across the year
        List<WindowResult> results = new ArrayList<>();
        int daysInYear = 365;

        for (int startDoy = 1; startDoy <= daysInYear - windowDays; startDoy += 5) {
            int endDoy = startDoy + windowDays;
            if (endDoy > daysInYear) break;

            List<Double> windowRets = new ArrayList<>();
            int yearsWithData = 0;
            int yearsPositive = 0;

            for (var entry : dailyReturns.entrySet()) {
                Map<Integer, Double> dReturns = entry.getValue();
                double sum = 0;
                boolean hasData = false;
                for (int d = startDoy; d < endDoy && d < daysInYear; d++) {
                    if (dReturns.containsKey(d)) {
                        sum += dReturns.get(d);
                        hasData = true;
                    }
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

            // Convert to month/day label
            LocalDate startDate = LocalDate.ofYearDay(2000, startDoy);
            LocalDate endDate = LocalDate.ofYearDay(2000, endDoy);
            String label = startDate.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
                + " " + startDate.getDayOfMonth() + " - "
                + endDate.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
                + " " + endDate.getDayOfMonth();

            results.add(new WindowResult(
                startDate.getMonthValue(), startDate.getDayOfMonth(),
                endDate.getMonthValue(), endDate.getDayOfMonth(),
                label, avgRet, medianRet, hitRate, sharpe,
                yearsWithData, yearsPositive
            ));
        }

        // Sort by hit rate descending
        results.sort((a, b) -> Double.compare(b.hitRate(), a.hitRate()));
        return results.stream().limit(10).toList();
    }

    // ====== Data loading ======

    private static final Set<Integer> OUTLIER_YEARS = Set.of(2008, 2020, 2022);
    private static final String BARS_DIR = "data/historical/bars";

    public static Map<Integer, List<Bar>> loadYears(String symbol, int fromYear, int toYear) throws Exception {
        Map<Integer, List<Bar>> result = new TreeMap<>();
        java.nio.file.Path barsDir = Paths.get(BARS_DIR);
        for (int year = fromYear; year <= toYear; year++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, year, barsDir);
                if (bars != null && !bars.isEmpty()) result.put(year, bars);
            } catch (Exception e) { /* skip */ }
        }
        return result;
    }

    // ====== Helpers ======

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 0) return (sorted.get(n/2 - 1) + sorted.get(n/2)) / 2.0;
        return sorted.get(n/2);
    }

    private static double stdDev(List<Double> values, double mean) {
        if (values.size() < 2) return 0;
        double sum = 0;
        for (double v : values) sum += (v - mean) * (v - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    // ====== CLI ======

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "USDCAD";
        int windowDays = args.length > 1 ? Integer.parseInt(args[1]) : 30;

        System.out.println("\n🔍 SLIDING WINDOW ANALYZER — " + symbol + " (" + windowDays + "d windows)");
        System.out.println("   Testing 2006-2026, excluding 2008/2020/2022\n");

        var results = findBestWindows(symbol, windowDays, 2006, 2026, true);

        if (results.isEmpty()) {
            System.out.println("  No windows with sufficient data found.");
            return;
        }

        System.out.println(String.format("%-22s | %8s | %8s | %5s | %6s | %s",
            "Window", "Avg Ret%", "Med Ret%", "Hit%", "Sharpe", "Years+"));
        System.out.println("-".repeat(70));

        for (WindowResult r : results) {
            String star = r.hitRate() >= 0.65 ? " ⭐" : r.hitRate() >= 0.60 ? " ★" : "";
            System.out.println(String.format("%-22s | %+8.2f | %+8.2f | %4.0f%% | %+5.2f | %d/%d%s",
                r.label(), r.avgReturn(), r.medianReturn(),
                r.hitRate() * 100, r.sharpe(),
                r.yearsPositive(), r.yearsObserved(), star));
        }
    }
}
