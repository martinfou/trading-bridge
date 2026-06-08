package com.martinfou.trading.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Resolves bar data for control-plane runs. */
public final class BarSourceResolver {

    public record BarsSource(
        String type,
        Integer count,
        @JsonProperty("year") String yearSpec,
        String path
    ) {

        /** Java-only; Jackson must use the canonical record (year is a string, e.g. {@code 2012} or {@code 2006-2025}). */
        @JsonIgnore
        public BarsSource(String type, Integer count, Integer year) {
            this(type, count, year != null ? String.valueOf(year) : null, null);
        }
    }

    private BarSourceResolver() {}

    public static List<Bar> load(BarsSource source, String symbol) throws IOException {
        return load(source, symbol, "H1");
    }

    public static List<Bar> load(BarsSource source, String symbol, String timeframe) throws IOException {
        if (source == null || source.type() == null) {
            throw new IllegalArgumentException("barsSource.type is required");
        }
        return switch (source.type().toLowerCase()) {
            case "sample" -> sampleBars(symbol, source.count() != null ? source.count() : 500);
            case "ci" -> loadCiSubset(symbol);
             case "year" -> {
                if (source.yearSpec() == null || source.yearSpec().isBlank()) {
                    throw new IllegalArgumentException("barsSource.year is required for type=year");
                }
                yield HistoricalDataLoader.loadYearSpec(
                    symbol, source.yearSpec(), timeframe, RuntimeDataPaths.defaultBarsDirectory());
            }
            case "file" -> loadFileBars(source, symbol);
            default -> throw new IllegalArgumentException("Unknown barsSource.type: " + source.type());
        };
     }
 
     private static List<Bar> loadFileBars(BarsSource source, String symbol) throws IOException {
         if (source.path() == null || source.path().isBlank()) {
             throw new IllegalArgumentException("barsSource.path is required for type=file");
         }
         Path path = Path.of(source.path().trim());
         if (!path.isAbsolute()) {
             Path root = EventStoreConfig.findRepoRoot();
             if (root == null) {
                 root = RuntimeDataPaths.defaultHistoricalDirectory().getParent();
             }
             if (root != null) {
                 path = root.resolve(path).normalize();
             }
         }
         return HistoricalDataLoader.loadPath(path, symbol);
    }

    static List<Bar> sampleBars(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        boolean jpy = symbol.contains("JPY");
        double price = jpy ? 185.0 : 1.08;
        double vol = jpy ? 0.15 : 0.002;
        var time = jpy
            ? Instant.parse("2003-01-01T00:00:00Z")
            : Instant.parse("2024-01-01T00:00:00Z");
        var rand = new Random(jpy ? 147 : 42);

        for (int i = 0; i < count; i++) {
            double open = price;
            double close = open + rand.nextGaussian() * vol;
            double high = Math.max(open, close) + Math.abs(rand.nextGaussian()) * vol * 0.5;
            double low = Math.min(open, close) - Math.abs(rand.nextGaussian()) * vol * 0.5;
            if (jpy) {
                price = Math.max(130.0, Math.min(250.0, close));
            } else {
                price = close;
            }
            bars.add(new Bar(symbol, time, open, high, low, close, 1000 + rand.nextInt(500)));
            time = time.plusSeconds(3600);
        }
        return bars;
    }

    private static List<Bar> loadCiSubset(String symbol) throws IOException {
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot == null) {
            throw new IllegalStateException("CI barsSource requires repo root (data/ci subset)");
        }
        Path ciFile = repoRoot.resolve("data/ci/EUR_USD_H1_subset.csv");
        return HistoricalDataLoader.loadPath(ciFile, symbol);
    }
}
