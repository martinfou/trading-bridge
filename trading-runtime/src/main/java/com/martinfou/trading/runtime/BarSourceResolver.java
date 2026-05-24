package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Resolves bar data for control-plane runs. */
public final class BarSourceResolver {

    public record BarsSource(String type, Integer count, Integer year) {}

    private BarSourceResolver() {}

    public static List<Bar> load(BarsSource source, String symbol) throws IOException {
        if (source == null || source.type() == null) {
            throw new IllegalArgumentException("barsSource.type is required");
        }
        return switch (source.type().toLowerCase()) {
            case "sample" -> sampleBars(symbol, source.count() != null ? source.count() : 500);
            case "year" -> {
                if (source.year() == null) {
                    throw new IllegalArgumentException("barsSource.year is required for type=year");
                }
                yield HistoricalDataLoader.loadYear(
                    symbol, source.year(), HistoricalDataLoader.DEFAULT_BARS_DIR);
            }
            default -> throw new IllegalArgumentException("Unknown barsSource.type: " + source.type());
        };
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
}
