package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes historical bar data to identify seasonal patterns.
 *
 * <p>Uses the merged .bars files to compute monthly return profiles,
 * day-of-week effects, and hour-of-day patterns per instrument.
 */
public class SeasonalityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SeasonalityAnalyzer.class);
    private final Path barsDir;

    public SeasonalityAnalyzer() {
        this.barsDir = resolveBarsDir();
    }

    private Path resolveBarsDir() {
        String dir = System.getProperty("trading.data.dir");
        if (dir != null) {
            return Path.of(dir);
        }
        Path ud = Path.of(System.getProperty("user.dir"));
        // Check if user.dir is the project root or a module subdir
        Path candidate = ud.resolve("data/historical/bars");
        if (Files.exists(candidate)) {
            return candidate;
        } else {
            // user.dir might be a module (e.g. trading-data), go up one level
            Path parent = ud.getParent();
            return parent != null ? parent.resolve("data/historical/bars") : ud.resolve("data/historical/bars");
        }
    }

    private Path getBarsDir() {
        return barsDir;
    }

    // Known seasonal adages checked by the analyzer
    private static final Map<String, int[]> SEASONAL_ADAGES = new LinkedHashMap<>();
    static {
        SEASONAL_ADAGES.put("Sell in May and Go Away", new int[]{5, 6, 7, 8});
        SEASONAL_ADAGES.put("Santa Claus Rally", new int[]{12});
        SEASONAL_ADAGES.put("January Effect", new int[]{1});
        SEASONAL_ADAGES.put("September Slump", new int[]{9});
        SEASONAL_ADAGES.put("Summer Doldrums", new int[]{7, 8});
        SEASONAL_ADAGES.put("Q4 Recovery", new int[]{10, 11, 12});
    }

    // Merge file patterns: forex pairs use format SYMBOL_H1_H1.bars
    private static final Map<String, String> MERGE_FILES = Map.ofEntries(
        Map.entry("GBP/JPY", "GBPJPY_H1_H1.bars"),
        Map.entry("XAU/USD", "XAU_USD_H1.bars"),
        Map.entry("EUR/USD", "EUR_USD_H1_H1.bars"),
        Map.entry("GBP/USD", "GBP_USD_H1_H1.bars"),
        Map.entry("USD/CAD", "USD_CAD_H1_H1.bars"),
        Map.entry("USD/JPY", "USD_JPY_H1_H1.bars"),
        Map.entry("AUD/USD", "AUD_USD_H1_H1.bars"),
        Map.entry("NZD/USD", "NZD_USD_H1_H1.bars"),
        Map.entry("USD/CHF", "USD_CHF_H1_H1.bars")
    );

    /**
     * Seasonal profile for a single instrument.
     */
    public record SeasonalProfile(
        String instrument,
        Map<String, Double> monthlyReturns,       // Month name → avg return %
        Map<String, Integer> monthlyWinRate,      // Month name → win rate %
        Map<String, Double> dayOfWeekReturns,     // Day name → avg return %
        Map<String, Double> hourOfDayReturns,     // Hour (00-23) → avg return %
        String bestMonth,
        String worstMonth,
        String bestDay,
        String worstDay,
        int bestHour,
        int worstHour,
        double avgYearlyReturn,
        double avgMonthlyReturn,
        double volatilityMonthly,
        int totalYears
    ) {
        @Override
        public String toString() {
            return String.format(
                "%s Seasonality | Best: %s (%.2f%%) Worst: %s (%.2f%%) | Best day: %s Worst: %s | Best hr: %02d Worst: %02d | Avg/yr: %.2f%%",
                instrument, bestMonth, monthlyReturns.getOrDefault(bestMonth, 0.0),
                worstMonth, monthlyReturns.getOrDefault(worstMonth, 0.0),
                bestDay, worstDay, bestHour, worstHour, avgYearlyReturn);
        }
    }

    /**
     * Check if "Sell in May" is active (May through August).
     */
    public boolean isSellInMayPeriod() {
        int m = LocalDate.now().getMonthValue();
        return m >= 5 && m <= 8;
    }

    /**
     * Analyze seasonality for a specific instrument.
     */
    public SeasonalProfile analyze(String instrument) throws Exception {
        List<Bar> bars = loadBars(instrument);
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("No data for " + instrument);
        }
        return computeProfile(instrument, bars);
    }

    /**
     * Analyze seasonality for all instruments.
     */
    public Map<String, SeasonalProfile> analyzeAll() {
        Map<String, SeasonalProfile> profiles = new LinkedHashMap<>();
        for (String instrument : MERGE_FILES.keySet()) {
            try {
                profiles.put(instrument, analyze(instrument));
            } catch (Exception e) {
                log.warn("Could not analyze {}: {}", instrument, e.getMessage());
            }
        }
        return profiles;
    }

    /**
     * Detect which seasonal adages apply right now.
     */
    public List<String> activeAdages() {
        int currentMonth = LocalDate.now().getMonthValue();
        List<String> active = new ArrayList<>();
        for (var entry : SEASONAL_ADAGES.entrySet()) {
            for (int m : entry.getValue()) {
                if (m == currentMonth) {
                    active.add(entry.getKey());
                    break;
                }
            }
        }
        return active;
    }

    // ── Profile computation ──

    private SeasonalProfile computeProfile(String instrument, List<Bar> bars) {
        Map<String, List<Double>> monthlyReturns = new LinkedHashMap<>();
        Map<String, List<Boolean>> monthlyWins = new LinkedHashMap<>();
        Map<String, List<Double>> dayOfWeekReturns = new LinkedHashMap<>();
        Map<Integer, List<Double>> hourReturns = new LinkedHashMap<>();

        // Initialize maps for all months/days/hours
        for (Month m : Month.values()) {
            monthlyReturns.put(m.name(), new ArrayList<>());
            monthlyWins.put(m.name(), new ArrayList<>());
        }
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            dayOfWeekReturns.put(day, new ArrayList<>());
        }
        for (int h = 0; h < 24; h++) {
            hourReturns.put(h, new ArrayList<>());
        }

        ZoneId utc = ZoneId.of("UTC");

        // ── Barres RÉELLES seulement ──
        // Une barre de carry (week-end, marché fermé) est plate par construction : high == low.
        // Les inclure gonfle le décompte et écrase les rendements (filtre documenté : high > low).
        List<Bar> real = realBars(bars);

        // ── Rendements mensuels VRAIS : clôture de fin de mois → clôture de fin de mois ──
        // (l'ancienne implémentation ajoutait le rendement d'UNE SEULE barre à la bascule de mois)
        Map<YearMonth, Double> monthEnd = monthEndCloses(real);
        YearMonth prevYm = null;
        for (var entry : monthEnd.entrySet()) {
            YearMonth ym = entry.getKey();
            if (prevYm != null && prevYm.plusMonths(1).equals(ym)) {
                double a = monthEnd.get(prevYm), c = entry.getValue();
                if (a > 0) {
                    double ret = (c - a) / a * 100.0;
                    monthlyReturns.get(ym.getMonth().name()).add(ret);
                    monthlyWins.get(ym.getMonth().name()).add(ret > 0);
                }
            }
            prevYm = ym;
        }

        // ── Effets jour / heure : rendements barre à barre sur barres réelles, en UTC ──
        for (int i = 1; i < real.size(); i++) {
            Bar prev = real.get(i - 1);
            Bar curr = real.get(i);
            if (prev.close() <= 0) continue;
            double ret = (curr.close() - prev.close()) / prev.close() * 100;

            ZonedDateTime currTime = ZonedDateTime.ofInstant(curr.timestamp(), utc);
            String dayName = currTime.getDayOfWeek().name();
            int hour = currTime.getHour();

            if (dayOfWeekReturns.containsKey(dayName)) dayOfWeekReturns.get(dayName).add(ret);
            hourReturns.get(hour).add(ret);
        }

        // Compute averages
        Map<String, Double> avgMonthlyRet = new LinkedHashMap<>();
        Map<String, Integer> monthlyWinPct = new LinkedHashMap<>();
        Map<String, Double> avgDayRet = new LinkedHashMap<>();
        Map<String, Double> avgHourRet = new LinkedHashMap<>();

        for (Month m : Month.values()) {
            var rets = monthlyReturns.get(m.name());
            avgMonthlyRet.put(m.name(), safeAvg(rets));
            monthlyWinPct.put(m.name(), (int) Math.round(safePct(monthlyWins.get(m.name()))));
        }
        for (var entry : dayOfWeekReturns.entrySet()) {
            avgDayRet.put(entry.getKey(), safeAvg(entry.getValue()));
        }
        for (var entry : hourReturns.entrySet()) {
            avgHourRet.put(String.format("%02d", entry.getKey()), safeAvg(entry.getValue()));
        }

        // ── Rendement annuel VRAI : clôture de fin d'année → clôture de fin d'année ──
        Map<Integer, Double> yearEnd = new TreeMap<>();
        for (Bar b : real) {
            yearEnd.put(ZonedDateTime.ofInstant(b.timestamp(), utc).getYear(), b.close());
        }
        List<Double> yearlyRets = new ArrayList<>();
        Integer prevYear = null;
        for (var entry : yearEnd.entrySet()) {
            if (prevYear != null && prevYear + 1 == entry.getKey()) {
                double a = yearEnd.get(prevYear), c = entry.getValue();
                if (a > 0) yearlyRets.add((c - a) / a * 100.0);
            }
            prevYear = entry.getKey();
        }
        double avgYearly = yearlyRets.isEmpty() ? 0 : yearlyRets.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double totalMonthlyAvg = avgMonthlyRet.values().stream()
            .mapToDouble(Double::doubleValue).average().orElse(0);
        double monthlyVol = Math.sqrt(avgMonthlyRet.values().stream()
            .mapToDouble(v -> Math.pow(v - totalMonthlyAvg, 2))
            .sum() / Math.max(1, avgMonthlyRet.size()));

        String bestMonth = avgMonthlyRet.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("JANUARY");
        String worstMonth = avgMonthlyRet.entrySet().stream()
            .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("JANUARY");
        String bestDay = avgDayRet.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("MONDAY");
        String worstDay = avgDayRet.entrySet().stream()
            .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("FRIDAY");
        int bestHour = Integer.parseInt(avgHourRet.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("00"));
        int worstHour = Integer.parseInt(avgHourRet.entrySet().stream()
            .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("00"));

        return new SeasonalProfile(instrument,
            avgMonthlyRet, monthlyWinPct,
            avgDayRet, avgHourRet,
            bestMonth, worstMonth, bestDay, worstDay,
            bestHour, worstHour,
            Math.round(avgYearly * 100.0) / 100.0,
            Math.round(totalMonthlyAvg * 100.0) / 100.0,
            Math.round(monthlyVol * 100.0) / 100.0,
            yearlyRets.size());
    }

    /** Barres RÉELLES : une barre de carry (marché fermé) est plate par construction (high == low). */
    static List<Bar> realBars(List<Bar> bars) {
        List<Bar> out = new ArrayList<>(bars.size());
        for (Bar b : bars) {
            if (b.high() > b.low()) out.add(b);
        }
        return out;
    }

    /** Clôtures de fin de mois sur barres réelles (UTC). */
    static Map<YearMonth, Double> monthEndCloses(List<Bar> realBars) {
        Map<YearMonth, Double> m = new TreeMap<>();
        for (Bar b : realBars) {
            m.put(YearMonth.from(ZonedDateTime.ofInstant(b.timestamp(), ZoneId.of("UTC"))), b.close());
        }
        return m;
    }

    /** Rendements mensuels vrais (%) par mois calendaire — exposition pour tests. */
    static Map<Integer, List<Double>> monthlyReturnsFromBars(List<Bar> realBars) {
        Map<YearMonth, Double> me = monthEndCloses(realBars);
        Map<Integer, List<Double>> out = new LinkedHashMap<>();
        YearMonth prev = null;
        for (var e : me.entrySet()) {
            if (prev != null && prev.plusMonths(1).equals(e.getKey())) {
                double a = me.get(prev), c = e.getValue();
                if (a > 0) out.computeIfAbsent(e.getKey().getMonthValue(), k -> new ArrayList<>())
                    .add((c - a) / a * 100.0);
            }
            prev = e.getKey();
        }
        return out;
    }

    // ── Bar loading ──

    public List<Bar> loadBars(String instrument) throws Exception {
        String fname = MERGE_FILES.get(instrument);
        if (fname == null) return List.of();
        Path path = getBarsDir().resolve(fname);
        if (!Files.exists(path)) return List.of();

        byte[] raw = Files.readAllBytes(path);
        int BAR_SIZE = 44;
        int count = raw.length / BAR_SIZE;
        List<Bar> bars = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int pos = i * BAR_SIZE;
            long ts = decodeEpochSeconds(bytesToLong(raw, pos));

            double o = bytesToDouble(raw, pos + 8);
            double h = bytesToDouble(raw, pos + 16);
            double l = bytesToDouble(raw, pos + 24);
            double c = bytesToDouble(raw, pos + 32);
            int v = bytesToInt(raw, pos + 40);

            bars.add(new Bar(instrument, java.time.Instant.ofEpochSecond(ts), o, h, l, c, v));
        }
        return bars;
    }

    /**
     * Décode l'horodatage brut d'une barre en SECONDES epoch.
     *
     * <p>Convention du repo : {@code BarStore} écrit {@code Instant.toEpochMilli()} et relit
     * {@code Instant.ofEpochMilli(raw)} dès que {@code raw > 1e12} — l'unité est la MILLISECONDE.
     * L'ancienne implémentation divisait par 1 000 000 (convention nanosecondes) : les 693 600
     * barres d'EUR_USD tombaient toutes entre le 14 et le 21 janvier 1970, ce qui vidait
     * {@code monthlyReturns}, {@code dayOfWeekReturns} et {@code hourOfDayReturns} (best = worst =
     * JANUARY, tous les hit rates à 0) — sortie silencieusement dégénérée pour tous les appelants.
     */
    static long decodeEpochSeconds(long raw) {
        if (raw > 100_000_000_000_000_000L) return raw / 1_000_000_000L; // nanosecondes
        if (raw > 100_000_000_000L) return raw / 1_000L;                 // millisecondes (cas réel)
        return raw;                                                      // secondes
    }

    private long bytesToLong(byte[] data, int offset) {
        return ((long)(data[offset] & 0xFF) << 56)
            | ((long)(data[offset+1] & 0xFF) << 48)
            | ((long)(data[offset+2] & 0xFF) << 40)
            | ((long)(data[offset+3] & 0xFF) << 32)
            | ((long)(data[offset+4] & 0xFF) << 24)
            | ((long)(data[offset+5] & 0xFF) << 16)
            | ((long)(data[offset+6] & 0xFF) << 8)
            | ((long)(data[offset+7] & 0xFF));
    }

    private double bytesToDouble(byte[] data, int offset) {
        return Double.longBitsToDouble(bytesToLong(data, offset));
    }

    private int bytesToInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
            | ((data[offset+1] & 0xFF) << 16)
            | ((data[offset+2] & 0xFF) << 8)
            | (data[offset+3] & 0xFF);
    }

    // ── Stats helpers ──

    private double safeAvg(List<Double> values) {
        if (values == null || values.isEmpty()) return 0;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double safePct(List<Boolean> values) {
        if (values == null || values.isEmpty()) return 0;
        long trues = values.stream().filter(b -> b).count();
        return (double) trues / values.size() * 100;
    }

    /**
     * Print a formatted seasonality report.
     */
    public void printReport(Map<String, SeasonalProfile> profiles) {
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  SEASONALITY ANALYSIS");
        System.out.println("  Active: " + String.join(", ", activeAdages()));
        System.out.println("═══════════════════════════════════════════");
        for (var entry : profiles.entrySet()) {
            var p = entry.getValue();
            System.out.printf("%-8s | Year: %+.2f%% | Best month: %s (%+.2f%%) | Worst: %s (%+.2f%%) | Best day: %s | Worst: %s | Best hr: %02d | Worst hr: %02d%n",
                p.instrument(), p.avgYearlyReturn(),
                p.bestMonth(), p.monthlyReturns().getOrDefault(p.bestMonth(), 0.0),
                p.worstMonth(), p.monthlyReturns().getOrDefault(p.worstMonth(), 0.0),
                p.bestDay(), p.worstDay(), p.bestHour(), p.worstHour());
        }
        System.out.println("═══════════════════════════════════════════\n");
    }
}
