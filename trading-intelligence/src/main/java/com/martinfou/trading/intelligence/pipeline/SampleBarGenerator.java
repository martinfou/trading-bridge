package com.martinfou.trading.intelligence.pipeline;

import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates synthetic sample bars for pipeline testing.
 * Avoids dependency on trading-examples (which would create a circular dep).
 */
public class SampleBarGenerator {

    private SampleBarGenerator() {}

    /**
     * Generate synthetic H1 bars for a given symbol.
     * Uses realistic price ranges per pair.
     */
    public static List<Bar> generate(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        boolean jpy = symbol.contains("JPY");
        boolean xau = symbol.contains("XAU");
        double price;
        double vol;

        if (xau) {
            price = 1950.0;
            vol = 5.0;
        } else if (jpy) {
            price = 140.0;
            vol = 0.15;
        } else {
            price = 1.08;
            vol = 0.002;
        }

        long seed = (long) symbol.hashCode();
        var rand = new Random(seed);
        var time = Instant.parse("2024-01-01T00:00:00Z");

        for (int i = 0; i < count; i++) {
            double open = price;
            double close = open + rand.nextGaussian() * vol;
            double high = Math.max(open, close) + Math.abs(rand.nextGaussian()) * vol * 0.5;
            double low = Math.min(open, close) - Math.abs(rand.nextGaussian()) * vol * 0.5;

            if (xau) {
                price = Math.max(1800.0, Math.min(2100.0, close));
            } else if (jpy) {
                price = Math.max(130.0, Math.min(160.0, close));
            } else {
                price = close;
            }

            bars.add(new Bar(symbol, time, open, high, low, close, 1000 + rand.nextInt(500)));
            time = time.plusSeconds(3600);
        }
        return bars;
    }
}
