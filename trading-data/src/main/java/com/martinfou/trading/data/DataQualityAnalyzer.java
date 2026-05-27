package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import java.io.IOException;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Data Quality Analyzer — Java version.
 *
 * <p>Scans .bars files for anomalies: gaps, duplicates, price spikes,
 * flatlines, year coverage. Parallel version using BarStore.</p>
 *
 * <p>Usage: {@code java ... DataQualityAnalyzer [--symbol GBP_JPY] [--report]}</p>
 *
 * <p>Le format .bars est Big-Endian avec timestamps en millisecondes,
 * compatible {@link BarStore} et MappedByteBuffer par défaut.</p>
 */
public final class DataQualityAnalyzer {

    private static final Path BARS_DIR = Path.of("data/historical/bars");
    private static final Path REPORT_DIR = Path.of("data/quality-reports");

    // Seuils
    private static final long MAX_H1_GAP_MS = 7200_000L;     // 2h max entre barres H1
    private static final double MAX_PRICE_SPIKE_PCT = 10.0;
    private static final int MIN_FLATLINE_HOURS = 12;
    private static final int MIN_BARS_PER_YEAR = 8000;
    private static final int MIN_BARS_PER_YEAR_FULL = 8736;

    private static final Map<String, double[]> PRICE_RANGES = Map.ofEntries(
        Map.entry("GBP_JPY", new double[]{120, 250}),
        Map.entry("EUR_USD", new double[]{0.8, 1.6}),
        Map.entry("GBP_USD", new double[]{1.0, 2.0}),
        Map.entry("USD_JPY", new double[]{75, 160}),
        Map.entry("USD_CAD", new double[]{0.9, 1.6}),
        Map.entry("AUD_USD", new double[]{0.5, 1.2}),
        Map.entry("NZD_USD", new double[]{0.4, 0.9}),
        Map.entry("USD_CHF", new double[]{0.8, 1.2}),
        Map.entry("XAU_USD", new double[]{200, 5000}),
        Map.entry("EUR_GBP", new double[]{0.7, 1.0}),
        Map.entry("EUR_JPY", new double[]{90, 175}),
        Map.entry("AUD_JPY", new double[]{55, 115}),
        Map.entry("CHF_JPY", new double[]{90, 130})
    );

    private final String symbol;
    private BarStore store;
    private List<Bar> bars;
    private final List<Issue> issues = new ArrayList<>();

    public record Issue(String type, String severity, int count, String detail) {}

    public DataQualityAnalyzer(String symbol) {
        this.symbol = symbol;
    }

    public static void main(String[] args) throws Exception {
        String symbol = null;
        boolean report = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--symbol" -> symbol = args[++i];
                case "--report" -> report = true;
                case "--list" -> { listAssets(); return; }
                default -> System.out.println("Unknown: " + args[i]);
            }
        }

        if (symbol != null) {
            var analyzer = new DataQualityAnalyzer(symbol.toUpperCase());
            var result = analyzer.run();
            printResult(result);
        } else {
            scanAll();
        }
    }

    static void listAssets() throws IOException {
        System.out.println("\n  Assets disponibles:");
        try (var files = Files.newDirectoryStream(BARS_DIR, "*_H1_H1.bars")) {
            for (var path : files) {
                String name = path.getFileName().toString();
                String sym = name.replace("_H1_H1.bars", "");
                long size = Files.size(path);
                long bars = size / 44;
                System.out.printf("    • %-12s %d barres%n", sym, bars);
            }
        }
    }

    record AssetResult(String symbol, long bars, Instant from, Instant to,
                       double firstPrice, double lastPrice, double priceMin, double priceMax,
                       List<Issue> issues) {}

    public AssetResult run() throws Exception {
        loadData();
        return analyze();
    }

    private void loadData() throws Exception {
        String fileName = symbol + "_H1_H1.bars";
        Path barFile = BARS_DIR.resolve(fileName);

        if (!Files.exists(barFile)) {
            throw new IOException("Fichier .bars non trouvé: " + barFile);
        }

        this.store = new BarStore(symbol, "H1", BARS_DIR);
        this.store.open();

        int count = store.count();
        this.bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(store.get(i));
        }
    }

    private AssetResult analyze() {
        int count = bars.size();
        if (count == 0) return null;

        var first = bars.get(0);
        var last = bars.get(count - 1);

        double priceMin = Double.MAX_VALUE;
        double priceMax = Double.MIN_VALUE;
        for (var b : bars) {
            priceMin = Math.min(priceMin, b.low());
            priceMax = Math.max(priceMax, b.high());
        }

        checkTimelineGaps();
        checkPriceSpikes();
        checkFlatlines();
        checkYearCoverage();
        checkPriceRange(priceMin, priceMax);

        return new AssetResult(symbol, count, first.timestamp(), last.timestamp(),
            first.close(), last.close(), priceMin, priceMax,
            List.copyOf(issues));
    }

    private void checkTimelineGaps() {
        if (bars.size() < 2) return;

        int totalGaps = 0;
        int bigGaps = 0;
        long maxGap = 0;

        for (int i = 1; i < bars.size(); i++) {
            long gap = bars.get(i).timestamp().toEpochMilli()
                     - bars.get(i - 1).timestamp().toEpochMilli();
            if (gap > MAX_H1_GAP_MS) {
                totalGaps++;
                if (gap > 72 * 3600_000L) bigGaps++;
                maxGap = Math.max(maxGap, gap);
            }
        }

        if (totalGaps > 0) {
            issues.add(new Issue("timeline_gaps",
                bigGaps > 5 ? "CRITICAL" : "WARN",
                totalGaps,
                String.format("%d total, %d majeurs (>72h), max %.1fh",
                    totalGaps, bigGaps, maxGap / 3600_000.0)));
        }
    }

    private void checkPriceSpikes() {
        if (bars.size() < 3) return;

        int spikes = 0;
        for (int i = 1; i < bars.size(); i++) {
            double prev = bars.get(i - 1).close();
            double high = bars.get(i).high();
            double low = bars.get(i).low();
            if (prev == 0) continue;

            double moveUp = (high - prev) / prev * 100;
            double moveDown = (prev - low) / prev * 100;
            if (Math.max(moveUp, moveDown) > MAX_PRICE_SPIKE_PCT) {
                spikes++;
            }
        }

        if (spikes > 0) {
            issues.add(new Issue("price_spikes",
                spikes < 5 ? "INFO" : "WARN", spikes,
                spikes + " spikes > " + MAX_PRICE_SPIKE_PCT + "%"));
        }
    }

    private void checkFlatlines() {
        if (bars.size() < 6) return;

        int flatCount = 0;
        int flatStart = -1;

        for (int i = 2; i < bars.size(); i++) {
            // Skip weekends (gaps > 2h)
            long gap = bars.get(i).timestamp().toEpochMilli()
                     - bars.get(i - 1).timestamp().toEpochMilli();
            if (gap > MAX_H1_GAP_MS) {
                if (flatStart >= 0) {
                    int duration = i - flatStart;
                    if (duration >= MIN_FLATLINE_HOURS) flatCount++;
                    flatStart = -1;
                }
                continue;
            }

            boolean dup = bars.get(i).open() == bars.get(i).high()
                       && bars.get(i).high() == bars.get(i).low()
                       && bars.get(i).low() == bars.get(i).close()
                       && bars.get(i).close() == bars.get(i - 1).close()
                       && bars.get(i - 1).open() == bars.get(i - 1).high()
                       && bars.get(i - 1).high() == bars.get(i - 1).low()
                       && bars.get(i - 1).low() == bars.get(i - 1).close()
                       && bars.get(i - 1).close() == bars.get(i - 2).close();

            if (dup) {
                if (flatStart < 0) flatStart = i - 2;
            } else if (flatStart >= 0) {
                int duration = i - flatStart;
                if (duration >= MIN_FLATLINE_HOURS) flatCount++;
                flatStart = -1;
            }
        }

        if (flatCount > 0) {
            issues.add(new Issue("holiday_duplicates", "INFO", flatCount,
                flatCount + " périodes de duplicates (vacances/jours fériés)"));
        }
    }

    private void checkYearCoverage() {
        Map<Integer, Integer> years = new HashMap<>();
        var zone = ZoneId.of("UTC");
        for (var b : bars) {
            int y = b.timestamp().atZone(zone).getYear();
            years.merge(y, 1, Integer::sum);
        }

        var missing = new ArrayList<String>();
        var partial = new ArrayList<String>();
        for (int y = 2006; y <= 2025; y++) {
            int count = years.getOrDefault(y, 0);
            if (count == 0) missing.add(String.valueOf(y));
            else if (count < MIN_BARS_PER_YEAR)
                partial.add(y + " (" + count + "/" + MIN_BARS_PER_YEAR_FULL + ")");
        }

        if (!missing.isEmpty() || !partial.isEmpty()) {
            issues.add(new Issue("year_coverage",
                missing.size() > 5 ? "CRITICAL" : "WARN",
                missing.size(),
                "Manquantes: " + missing + " | Partielles: " + partial));
        }
    }

    private void checkPriceRange(double minP, double maxP) {
        var range = PRICE_RANGES.get(symbol);
        if (range == null) return;

        if (minP < range[0] || maxP > range[1]) {
            issues.add(new Issue("price_range", "INFO", 0,
                String.format("Prix %.2f-%.2f (attendu: %.0f-%.0f)", minP, maxP, range[0], range[1])));
        }
    }

    // ─── Scan All ──────────────────────────────────────────────────────

    static void scanAll() throws Exception {
        System.out.println("\n" + "=" .repeat(70));
        System.out.println("  DATA QUALITY REPORT — FOREX HISTORICAL DATA");
        System.out.println("=" .repeat(70));

        int totalBars = 0;
        int totalIssues = 0;

        var assets = List.of("GBP_JPY", "XAU_USD", "EUR_USD", "GBP_USD", "USD_JPY",
                             "USD_CAD", "AUD_USD", "NZD_USD", "USD_CHF");

        for (var sym : assets) {
            try {
                var analyzer = new DataQualityAnalyzer(sym);
                var result = analyzer.run();

                totalBars += result.bars;
                String sevIcon = result.issues.isEmpty() ? "✅" :
                    result.issues.stream().anyMatch(i -> i.severity().equals("CRITICAL")) ? "🔴" : "⚠️";

                System.out.printf("%n  %s %s%n  %s%n", sevIcon, sym, "─".repeat(50));
                System.out.printf("     Barres:   %d%n", result.bars);
                System.out.printf("     Période:  %s → %s%n",
                    result.from.toString().substring(0,10), result.to.toString().substring(0,10));
                System.out.printf("     Prix:     %.3f → %.3f%n", result.priceMin, result.priceMax);

                for (var issue : result.issues) {
                    String s = switch (issue.severity()) {
                        case "CRITICAL" -> "🔴";
                        case "WARN" -> "⚠️";
                        default -> "ℹ️";
                    };
                    System.out.printf("     %s %s: %s%n", s, issue.type(), issue.detail());
                    totalIssues++;
                }

                if (result.issues.isEmpty()) {
                    System.out.println("     ✅ Aucun problème détecté");
                }

            } catch (Exception e) {
                System.out.printf("%n  ❌ %s: %s%n", sym, e.getMessage());
            }
        }

        System.out.printf("%n%s%n", "=".repeat(70));
        System.out.printf("  Total: %d assets, %d barres, %d problèmes%n",
            assets.size(), totalBars, totalIssues);
        System.out.printf("%s%n%n", "=".repeat(70));
    }

    static void printResult(AssetResult r) {
        System.out.printf("""
            %s: %d barres
              Période:  %s → %s
              Prix:     %.3f → %.3f
            """, r.symbol, r.bars, r.from.toString().substring(0,10), r.to.toString().substring(0,10),
                r.priceMin, r.priceMax);
        for (var issue : r.issues) {
            System.out.printf("  [%s] %s: %s%n", issue.severity(), issue.type(), issue.detail());
        }
    }
}
