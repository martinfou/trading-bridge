package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.DataLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        List<Bar> bars = loadYearSpec(symbol, yearSpec, DEFAULT_BARS_DIR);
        return new LoadResult(bars, symbol, describeYearSpecSource(symbol, yearSpec, DEFAULT_BARS_DIR));
    }

    /** Loads bars from a file path (.bars or CSV). */
    public static LoadResult loadFile(Path path, String defaultSymbol) throws IOException {
        String symbol = inferSymbol(path, defaultSymbol);
        return new LoadResult(loadPath(path, symbol), symbol, path.toString());
    }

    public static List<Bar> loadPath(Path path, String symbol) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".bars")) {
            return loadBarStoreFile(path, symbolFromBarStorePath(path, symbol));
        }
        if (name.endsWith(".csv")) {
            String sym = inferSymbol(path, symbol);
            return DataLoader.loadAutoCSV(path, sym);
        }
        throw new IOException("Unsupported file: " + path);
    }

    public static List<Bar> loadYear(String symbol, int year, Path barsDir) throws IOException {
        Path csv = findDukascopyCsv(symbol, year);
        if (csv != null) {
            return DataLoader.loadDukascopyCSV(csv, symbol);
        }
        var store = new BarStore(symbol, "H1_" + year, barsDir);
        store.open();
        return store.toList();
    }

    public static List<Bar> loadYearRange(String symbol, int startYear, int endYear, Path barsDir) throws IOException {
        var merged = new ArrayList<Bar>();
        for (int y = startYear; y <= endYear; y++) {
            merged.addAll(loadYear(symbol, y, barsDir));
        }
        return merged;
    }

    public static List<Bar> loadYearSpec(String symbol, String spec, Path barsDir) throws IOException {
        if (spec.contains("-") && spec.matches("\\d{4}-\\d{4}")) {
            String[] parts = spec.split("-");
            return loadYearRange(symbol, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), barsDir);
        }
        return loadYear(symbol, Integer.parseInt(spec), barsDir);
    }

    public static String inferSymbol(Path path, String fallback) {
        String name = path.getFileName().toString().toLowerCase();
        Matcher m = PAIR_IN_NAME.matcher(name);
        if (m.find()) {
            return pairToSymbol(m.group(1));
        }
        if (name.contains("_h1_")) {
            return name.substring(0, name.indexOf("_h1_")).toUpperCase();
        }
        return fallback;
    }

    private static List<Bar> loadBarStoreFile(Path path, String symbol) throws IOException {
        String filename = path.getFileName().toString();
        int h1 = filename.indexOf("_H1_");
        String storeKey = h1 > 0 ? filename.substring(h1 + 4, filename.length() - 5) : "H1";
        var store = new BarStore(symbol, "H1_" + storeKey, path.getParent());
        store.open();
        return store.toList();
    }

    private static String symbolFromBarStorePath(Path path, String fallback) {
        String name = path.getFileName().toString();
        int idx = name.indexOf("_H1_");
        if (idx > 0) {
            return name.substring(0, idx);
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

    private static Path findDukascopyCsv(String symbol, int year) throws IOException {
        if (!Files.isDirectory(DEFAULT_CSV_DIR)) return null;
        String pair = symbol.replace("_", "").toLowerCase();
        String yearStr = String.valueOf(year);
        try (var stream = Files.list(DEFAULT_CSV_DIR)) {
            return stream
                .filter(p -> p.toString().endsWith(".csv"))
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.startsWith(pair + "-h1") && n.contains(yearStr);
                })
                .findFirst()
                .orElse(null);
        }
    }

    private static String describeYearSpecSource(String symbol, String spec, Path barsDir) throws IOException {
        if (spec.contains("-") && spec.matches("\\d{4}-\\d{4}")) {
            String[] parts = spec.split("-");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            Path csv = findDukascopyCsv(symbol, start);
            if (csv != null) {
                return csv + " … " + end;
            }
            return barsDir.resolve(symbol + "_H1_" + spec + ".bars").toString();
        }
        int year = Integer.parseInt(spec);
        Path csv = findDukascopyCsv(symbol, year);
        if (csv != null) {
            return csv.toString();
        }
        return barsDir.resolve(symbol + "_H1_" + year + ".bars").toString();
    }

    public record LoadResult(List<Bar> bars, String symbol, String source) {}
}
