package com.martinfou.trading.genetics;

import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test utility for generating synthetic bar data for genetic algorithm tests.
 */
public final class TestBarData {

    private TestBarData() {}

    /**
     * Generates a synthetic price series with a slight upward trend and noise.
     *
     * @param barCount  number of bars to generate
     * @param startPrice starting price
     * @return list of generated bars
     */
    public static List<Bar> generateTrendingBars(int barCount, double startPrice) {
        var rng = ThreadLocalRandom.current();
        List<Bar> bars = new ArrayList<>(barCount);
        double price = startPrice;
        for (int i = 0; i < barCount; i++) {
            // Slight upward trend with noise
            double change = (rng.nextDouble() - 0.45) * 0.02;
            price = price * (1 + change);
            double open = price;
            double close = price * (1 + (rng.nextDouble() - 0.5) * 0.01);
            double high = Math.max(open, close) * (1 + rng.nextDouble() * 0.005);
            double low = Math.min(open, close) * (1 - rng.nextDouble() * 0.005);
            long volume = rng.nextLong(100, 10000);
            bars.add(new Bar("EURUSD", Instant.ofEpochSecond(86400L * i), open, high, low, close, volume));
        }
        return bars;
    }

    /**
     * Generates a sine-wave price pattern for predictable test behaviour.
     *
     * @param barCount number of bars
     * @param basePrice centre price
     * @param amplitude amplitude of the sine wave
     * @return list of generated bars
     */
    public static List<Bar> generateSineBars(int barCount, double basePrice, double amplitude) {
        List<Bar> bars = new ArrayList<>(barCount);
        for (int i = 0; i < barCount; i++) {
            double phase = (double) i / barCount * 2 * Math.PI * 4; // 4 full cycles
            double price = basePrice + amplitude * Math.sin(phase);
            double open = price;
            double close = price * 1.001;
            double high = Math.max(open, close) * 1.001;
            double low = Math.min(open, close) * 0.999;
            bars.add(new Bar("EURUSD", Instant.ofEpochSecond(86400L * i), open, high, low, close, 1000));
        }
        return bars;
    }
}
