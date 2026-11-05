package com.martinfou.trading.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Indexes which symbols and years exist under {@code data/historical/}. */
public final class HistoricalDataCatalog {

    private static final Pattern YEAR_IN_NAME = Pattern.compile("(19|20)\\d{2}");

    private HistoricalDataCatalog() {}

    public record SymbolAvailability(
        String symbol,
        List<Integer> years,
        int minYear,
        int maxYear,
        String suggestedRange,
        List<Integer> gaps
    ) {}

    public static List<String> listSymbols(Path barsDir, Path csvDir) throws IOException {
        SortedSet<String> symbols = new TreeSet<>();
        collectSymbolsFromBars(barsDir, symbols);
        collectSymbolsFromCsv(csvDir, symbols);
        return List.copyOf(symbols);
    }

    public static SymbolAvailability availability(String symbol, Path barsDir, Path csvDir) throws IOException {
        return availability(symbol, null, barsDir, csvDir);
    }

    /**
     * Availability for a specific strategy timeframe: a year counts when native bars exist at exactly that
     * timeframe, or at a finer one the loader can aggregate into it. {@code timeframe == null} counts any
     * standard timeframe, which is what the coverage grid means by "data is present".
     */
    public static SymbolAvailability availability(String symbol, String timeframe, Path barsDir, Path csvDir)
            throws IOException {
        SortedSet<Integer> years = yearsForSymbol(symbol, timeframe, barsDir, csvDir);
        if (years.isEmpty()) {
            return new SymbolAvailability(symbol, List.of(), 0, 0, "", List.of());
        }
        int min = years.first();
        int max = years.last();
        List<Integer> yearList = List.copyOf(years);
        List<Integer> gaps = findGaps(years, min, max);
        String suggested = gaps.isEmpty() ? min + "-" + max : yearList.toString();
        return new SymbolAvailability(symbol, yearList, min, max, suggested, gaps);
    }

    public static Map<String, Object> toMap(SymbolAvailability availability) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", availability.symbol());
        map.put("years", availability.years());
        map.put("minYear", availability.minYear());
        map.put("maxYear", availability.maxYear());
        map.put("suggestedRange", availability.suggestedRange());
        map.put("gaps", availability.gaps());
        map.put("yearCount", availability.years().size());
        return Map.copyOf(map);
    }

    static SortedSet<Integer> yearsForSymbol(String symbol, Path barsDir, Path csvDir) throws IOException {
        return yearsForSymbol(symbol, null, barsDir, csvDir);
    }

    static SortedSet<Integer> yearsForSymbol(String symbol, String timeframe, Path barsDir, Path csvDir)
            throws IOException {
        SortedSet<Integer> years = new TreeSet<>();
        String prefix = symbol.toUpperCase(Locale.ROOT) + "_";
        String target = (timeframe == null || timeframe.isBlank()) ? null : timeframe.toUpperCase(Locale.ROOT);
        if (Files.isDirectory(barsDir)) {
            try (var stream = Files.list(barsDir)) {
                stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.toUpperCase(Locale.ROOT).startsWith(prefix))
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".bars"))
                    .forEach(name -> {
                        String rest = name.substring(prefix.length());
                        int sep = rest.indexOf('_');
                        if (sep <= 0) {
                            return;
                        }
                        String tfToken = rest.substring(0, sep).toUpperCase(Locale.ROOT);
                        if (!isStandardTimeframe(tfToken)) {
                            return; // unknown token, not one of the standard candle sizes
                        }
                        if (target != null && !HistoricalDataLoader.canServe(target, tfToken)) {
                            return;
                        }
                        parseYearSuffix(rest, sep + 1, years);
                    });
            }
        }
        if (Files.isDirectory(csvDir) && (target == null || HistoricalDataLoader.canServe(target, "H1"))) {
            String pair = symbol.replace("_", "").toLowerCase(Locale.ROOT);
            try (var stream = Files.list(csvDir)) {
                stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".csv"))
                    .filter(name -> name.startsWith(pair + "-h1") || name.startsWith(pair + "_h1"))
                    .forEach(name -> extractYears(name, years));
            }
        }
        return years;
    }

    private static boolean isStandardTimeframe(String tf) {
        for (String standard : HistoricalDataLoader.STANDARD_TFS) {
            if (standard.equals(tf)) {
                return true;
            }
        }
        return false;
    }

    private static void collectSymbolsFromBars(Path barsDir, SortedSet<String> symbols) throws IOException {
        if (!Files.isDirectory(barsDir)) {
            return;
        }
        try (var stream = Files.list(barsDir)) {
            stream.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".bars"))
                .forEach(name -> {
                    String upper = name.toUpperCase(Locale.ROOT);
                    for (String tf : HistoricalDataLoader.STANDARD_TFS) {
                        int idx = upper.indexOf("_" + tf + "_");
                        if (idx > 0) {
                            symbols.add(upper.substring(0, idx));
                            return;
                        }
                    }
                });
        }
    }

    private static void collectSymbolsFromCsv(Path csvDir, SortedSet<String> symbols) throws IOException {
        if (!Files.isDirectory(csvDir)) {
            return;
        }
        try (var stream = Files.list(csvDir)) {
            stream.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".csv"))
                .forEach(name -> symbols.add(HistoricalDataLoader.inferSymbol(Path.of(name), "")));
        }
        symbols.removeIf(String::isBlank);
    }

    private static void parseYearSuffix(String name, int yearStart, SortedSet<Integer> years) {
        int end = name.length() - 5;
        if (end <= yearStart) {
            return;
        }
        try {
            years.add(Integer.parseInt(name.substring(yearStart, end)));
        } catch (NumberFormatException ignored) {
            // skip malformed filenames
        }
    }

    private static void extractYears(String name, SortedSet<Integer> years) {
        Matcher matcher = YEAR_IN_NAME.matcher(name);
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group());
            if (year >= 1990 && year <= 2035) {
                years.add(year);
            }
        }
    }

    private static List<Integer> findGaps(SortedSet<Integer> years, int min, int max) {
        if (max - min > 50) {
            return List.of();
        }
        return java.util.stream.IntStream.rangeClosed(min, max)
            .filter(y -> !years.contains(y))
            .boxed()
            .collect(Collectors.toCollection(ArrayList::new));
    }
}
