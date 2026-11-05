package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.DataLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single entry point for historical bar data under {@code data/historical/}.
 * Dukascopy CSV is preferred when present; {@link BarStore} {@code .bars} is the fallback.
 * Timestamps in {@code .bars} files are epoch millis (legacy second-based files are still readable).
 */
public final class HistoricalDataLoader {

    public static final Path DEFAULT_BARS_DIR = Path.of("data/historical/bars");
    public static final Path DEFAULT_CSV_DIR = Path.of("data/historical/dukascopy");

    private static final Pattern PAIR_IN_NAME = Pattern.compile("(eurusd|gbpusd|usdjpy|gbpjpy|usdcad|audusd|nzdusd|usdchf)");

    private HistoricalDataLoader() {}

    /**
     * Resolves data from CLI args after the strategy name.
     * <ul>
     *   <li>{@code EUR_USD 2012} — BarStore year file</li>
     *   <li>{@code EUR_USD 2006-2012} — merge multiple years</li>
     *   <li>{@code path/to/EUR_USD_H1_2012.bars}</li>
     *   <li>{@code path/to/eurusd-h1-bid-....csv}</li>
     * </ul>
     */
    public static LoadResult loadFromArgs(String defaultSymbol, String... dataArgs) throws IOException {
        return loadFromArgsWithTimeframe(defaultSymbol, "H1", dataArgs);
    }

    public static LoadResult loadFromArgsWithTimeframe(String defaultSymbol, String strategyTimeframe, String... dataArgs) throws IOException {
        if (dataArgs.length == 0) {
            throw new IOException("Provide --sample, SYMBOL YEAR, or a file path");
        }

        String arg0 = dataArgs[0];
        if (arg0.endsWith(".bars") || arg0.endsWith(".csv")) {
            Path path = Path.of(arg0);
            String symbol = dataArgs.length >= 2 ? dataArgs[1] : inferSymbol(path, defaultSymbol);
            return new LoadResult(loadPath(path, symbol), symbol, path.toString());
        }

        if (Files.exists(Path.of(arg0))) {
            Path path = Path.of(arg0);
            String symbol = dataArgs.length >= 2 ? dataArgs[1] : inferSymbol(path, defaultSymbol);
            return new LoadResult(loadPath(path, symbol), symbol, path.toString());
        }

        String symbol = arg0.contains("_") ? arg0 : pairToSymbol(arg0);
        if (dataArgs.length < 2) {
            throw new IOException("Usage: " + symbol + " <YEAR> or " + symbol + " <START-END>");
        }
        String yearSpec = dataArgs[1];
        String tf = strategyTimeframe != null && !strategyTimeframe.isBlank() ? strategyTimeframe.toUpperCase() : "H1";
        List<Bar> bars = loadYearSpecWithFallback(symbol, yearSpec, tf, DEFAULT_BARS_DIR);
        return new LoadResult(bars, symbol, describeYearSpecSource(symbol, yearSpec, tf, DEFAULT_BARS_DIR));
    }

    /** Standard candle sizes, finest first. */
    static final String[] STANDARD_TFS = {"M1", "M5", "M15", "M30", "H1", "H4", "D1", "W1"};

    /** Length of one candle of {@code tf} in minutes. */
    static int tfMinutes(String tf) {
        if (tf == null || tf.isBlank()) {
            throw new IllegalArgumentException("Blank timeframe");
        }
        String u = tf.trim().toUpperCase();
        int n = u.length() > 1 ? Integer.parseInt(u.substring(1)) : 1;
        if (n <= 0) {
            throw new IllegalArgumentException("Timeframe multiplier must be positive: " + tf);
        }
        return switch (u.charAt(0)) {
            case 'M' -> n;
            case 'H' -> n * 60;
            case 'D' -> n * 1440;
            case 'W' -> n * 10080;
            default -> throw new IllegalArgumentException("Unsupported timeframe: " + tf);
        };
    }

    /**
     * True when native {@code base} candles can be aggregated into {@code target}.
     * Session-aligned D1/W1 candles (17:00/18:00 NY) only aggregate from {@code <= H1} so UTC-aligned H4
     * bars never straddle the daily rollover boundary.
     */
    static boolean canServe(String target, String base) {
        int b = tfMinutes(base);
        int t = tfMinutes(target);
        if (b == t) {
            return true;
        }
        if (t >= 1440 && b > 60) {
            return false;
        }
        return b < t && t % b == 0;
    }

    /** Loads native bars (.bars store or direct IBKR/standard CSV) for exactly this timeframe, or throws IOException. */
    private static List<Bar> loadNative(String symbol, String spec, String tf, Path barsDir) throws IOException {
        try {
            return BarStore.sanitizeChronological(loadYearSpec(symbol, spec, tf, barsDir));
        } catch (IOException | RuntimeException ignored) {
            // fall through to direct CSV
        }
        Path directCsv = Path.of("data/historical/csv", symbol.toUpperCase() + "_" + tf.toUpperCase() + "_" + spec + ".csv");
        if (Files.isRegularFile(directCsv)) {
            return BarStore.sanitizeChronological(DataLoader.loadAutoCSV(directCsv, symbol));
        }
        throw new IOException("No native " + tf + " data for " + symbol + " " + spec);
    }

    /**
     * Loads bars for any standard timeframe (M1..W1). Uses native data when present (Dukascopy .bars or
     * IBKR futures CSV); otherwise aggregates from the coarsest finer timeframe that is available
     * (e.g. futures: M30 CSV -> H1/H4/D1; M1 -> M5/M15/M30).
     */
    public static List<Bar> loadYearSpecWithFallback(String symbol, String spec, String tf, Path barsDir) throws IOException {
        try {
            return loadNative(symbol, spec, tf, barsDir);
        } catch (IOException e) {
            if ("M1".equalsIgnoreCase(tf)) {
                throw e; // nothing finer to aggregate from
            }
        }
        int target = tfMinutes(tf);
        List<Bar> source = null;
        for (int i = STANDARD_TFS.length - 1; i >= 0 && source == null; i--) {
            String base = STANDARD_TFS[i];
            int m = tfMinutes(base);
            if (m >= target || !canServe(tf, base)) {
                continue;
            }
            try {
                source = loadNative(symbol, spec, base, barsDir);
            } catch (IOException ignored) {
                // try next finer base
            }
        }
        if (source == null) {
            throw new IOException("No " + tf + " data (native or aggregable) for " + symbol + " " + spec);
        }
        com.martinfou.trading.core.BarAggregator agg = new com.martinfou.trading.core.BarAggregator(symbol, tf);
        List<Bar> aggregated = new ArrayList<>();
        for (Bar b : source) {
            boolean newPeriod = agg.isNewPeriod(b);
            agg.add(b);
            if (newPeriod) {
                agg.getLastCompletedBar().ifPresent(aggregated::add);
            }
        }
        agg.completePeriod();
        agg.getLastCompletedBar().ifPresent(aggregated::add);
        return BarStore.sanitizeChronological(aggregated);
    }

    /** Loads bars from a file path (.bars or CSV). */
    public static LoadResult loadFile(Path path, String defaultSymbol) throws IOException {
        String symbol = inferSymbol(path, defaultSymbol);
        return new LoadResult(loadPath(path, symbol), symbol, path.toString());
    }

    public static List<Bar> loadPath(Path path, String symbol) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".bars")) {
            return BarStore.sanitizeChronological(loadBarStoreFile(path, symbolFromBarStorePath(path, symbol)));
        }
        if (name.endsWith(".csv")) {
            String sym = inferSymbol(path, symbol);
            return BarStore.sanitizeChronological(DataLoader.loadAutoCSV(path, sym));
        }
        throw new IOException("Unsupported file: " + path);
    }

    public static List<Bar> loadYear(String symbol, int year, Path barsDir) throws IOException {
        return loadYear(symbol, year, "H1", barsDir);
    }

    public static List<Bar> loadYear(String symbol, int year, String tf, Path barsDir) throws IOException {
        String timeframe = tf != null ? tf.toUpperCase() : "H1";
        Optional<Path> csv = findDukascopyCsv(symbol, year, timeframe);
        if (csv.isPresent()) {
            return BarStore.sanitizeChronological(DataLoader.loadDukascopyCSV(csv.get(), symbol));
        }
        var store = new BarStore(symbol, timeframe + "_" + year, barsDir);
        store.open();
        return store.toList();
    }

    public static List<Bar> loadYearRange(String symbol, int startYear, int endYear, Path barsDir) throws IOException {
        return loadYearRange(symbol, startYear, endYear, "H1", barsDir);
    }

    public static List<Bar> loadYearRange(String symbol, int startYear, int endYear, String tf, Path barsDir) throws IOException {
        var merged = new ArrayList<Bar>();
        for (int y = startYear; y <= endYear; y++) {
            merged.addAll(loadYear(symbol, y, tf, barsDir));
        }
        return BarStore.sanitizeChronological(merged);
    }

    public static List<Bar> loadYearSpec(String symbol, String spec, Path barsDir) throws IOException {
        return loadYearSpec(symbol, spec, "H1", barsDir);
    }

    public static List<Bar> loadYearSpec(String symbol, String spec, String tf, Path barsDir) throws IOException {
        if (spec.equalsIgnoreCase("all")) {
            return loadAllAvailable(symbol, tf, barsDir, DEFAULT_CSV_DIR).bars();
        }
        if (spec.contains("-") && spec.matches("\\d{4}-\\d{4}")) {
            String[] parts = spec.split("-");
            return loadYearRange(symbol, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), tf, barsDir);
        }
        return loadYear(symbol, Integer.parseInt(spec), tf, barsDir);
    }

    /** Loads every year indexed for {@code symbol} under bars/ and dukascopy CSV dirs. */
    public static LoadResult loadAllAvailable(String symbol) throws IOException {
        return loadAllAvailable(symbol, "H1", DEFAULT_BARS_DIR, DEFAULT_CSV_DIR);
    }

    public static LoadResult loadAllAvailable(String symbol, Path barsDir, Path csvDir) throws IOException {
        return loadAllAvailable(symbol, "H1", barsDir, csvDir);
    }

    public static LoadResult loadAllAvailable(String symbol, String tf, Path barsDir, Path csvDir) throws IOException {
        String timeframe = (tf != null && !tf.isBlank()) ? tf.trim().toUpperCase() : "H1";
        HistoricalDataCatalog.SymbolAvailability availability =
            HistoricalDataCatalog.availability(symbol, timeframe, barsDir, csvDir);
        if (availability.years().isEmpty()) {
            throw new IOException("No historical data for " + symbol);
        }
        var merged = new ArrayList<Bar>();
        for (int year : availability.years()) {
            merged.addAll(loadYearSpecWithFallback(symbol, String.valueOf(year), timeframe, barsDir));
        }
        String source = availability.minYear() + "-" + availability.maxYear()
            + " (" + availability.years().size() + " year(s))";
        return new LoadResult(BarStore.sanitizeChronological(merged), symbol, source);
    }

    public static String inferSymbol(Path path, String fallback) {
        String name = path.getFileName().toString().toLowerCase();
        Matcher m = PAIR_IN_NAME.matcher(name);
        if (m.find()) {
            return pairToSymbol(m.group(1));
        }
        for (String candidate : STANDARD_TFS) {
            String token = "_" + candidate.toLowerCase() + "_";
            int idx = name.indexOf(token);
            if (idx > 0) {
                return name.substring(0, idx).toUpperCase();
            }
        }
        return fallback;
    }

    private static List<Bar> loadBarStoreFile(Path path, String symbol) throws IOException {
        String filename = path.getFileName().toString();
        String tf = "H1";
        for (String candidate : STANDARD_TFS) {
            if (filename.contains("_" + candidate + "_")) {
                tf = candidate;
                break;
            }
        }
        int idx = filename.indexOf("_" + tf + "_");
        String storeKey = idx > 0 ? filename.substring(idx + tf.length() + 2, filename.length() - 5) : tf;
        var store = new BarStore(symbol, tf + "_" + storeKey, path.getParent());
        store.open();
        return store.toList();
    }

    private static String symbolFromBarStorePath(Path path, String fallback) {
        String name = path.getFileName().toString();
        for (String candidate : STANDARD_TFS) {
            String token = "_" + candidate + "_";
            int idx = name.indexOf(token);
            if (idx > 0) {
                return name.substring(0, idx);
            }
        }
        return inferSymbol(path, fallback);
    }

    private static String pairToSymbol(String pair) {
        return switch (pair.toLowerCase()) {
            case "eurusd" -> "EUR_USD";
            case "gbpusd" -> "GBP_USD";
            case "usdjpy" -> "USD_JPY";
            case "gbpjpy" -> "GBP_JPY";
            case "usdcad" -> "USD_CAD";
            case "audusd" -> "AUD_USD";
            case "nzdusd" -> "NZD_USD";
            case "usdchf" -> "USD_CHF";
            default -> pair.toUpperCase();
        };
    }

    private static Optional<Path> findDukascopyCsv(String symbol, int year) throws IOException {
        return findDukascopyCsv(symbol, year, "H1");
    }

    private static Optional<Path> findDukascopyCsv(String symbol, int year, String tf) throws IOException {
        if (!Files.isDirectory(DEFAULT_CSV_DIR)) return Optional.empty();
        String pair = symbol.replace("_", "").toLowerCase();
        String yearStr = String.valueOf(year);
        String tfLower = tf.toLowerCase();
        try (var stream = Files.list(DEFAULT_CSV_DIR)) {
            return stream
                .filter(p -> p.toString().endsWith(".csv"))
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.startsWith(pair + "-" + tfLower) && n.contains(yearStr);
                })
                .findFirst();
        }
    }

    private static String describeYearSpecSource(String symbol, String spec, Path barsDir) throws IOException {
        return describeYearSpecSource(symbol, spec, "H1", barsDir);
    }

    private static String describeYearSpecSource(String symbol, String spec, String tf, Path barsDir) throws IOException {
        String timeframe = tf != null ? tf.toUpperCase() : "H1";
        if (spec.equalsIgnoreCase("all")) {
            return "All available years";
        }
        if (spec.contains("-") && spec.matches("\\d{4}-\\d{4}")) {
            String[] parts = spec.split("-");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            Optional<Path> csv = findDukascopyCsv(symbol, start, timeframe);
            if (csv.isPresent()) {
                return csv.get() + " … " + end;
            }
            return barsDir.resolve(symbol + "_" + timeframe + "_" + spec + ".bars").toString();
        }
        int year = Integer.parseInt(spec);
        Optional<Path> csv = findDukascopyCsv(symbol, year, timeframe);
        if (csv.isPresent()) {
            return csv.get().toString();
        }
        return barsDir.resolve(symbol + "_" + timeframe + "_" + year + ".bars").toString();
    }

    public record LoadResult(List<Bar> bars, String symbol, String source) {}
}
