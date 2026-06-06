package com.martinfou.trading.backtest.harness;

import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Synthetic UTC bar series for harness strategy tests. */
public final class HarnessTestBars {

    private HarnessTestBars() {}

    /** {@code days} × 24 H1 bars starting at {@code start} UTC midnight, hours 0..23 each day. */
    public static List<Bar> h1Days(String symbol, LocalDate start, int days) {
        List<Bar> bars = new ArrayList<>(days * 24);
        Instant base = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (int d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                Instant ts = base.plus(d * 24L + h, ChronoUnit.HOURS);
                bars.add(bar(symbol, ts, 1.1000 + h * 0.0001));
            }
        }
        return bars;
    }

    /** Seven daily bars Monday through Sunday at 12:00 UTC (includes weekend). */
    public static List<Bar> weekMonToSun(String symbol, LocalDate monday) {
        List<Bar> bars = new ArrayList<>(7);
        for (int d = 0; d < 7; d++) {
            Instant ts = monday.plusDays(d).atTime(12, 0).toInstant(ZoneOffset.UTC);
            bars.add(bar(symbol, ts, 1.1000 + d * 0.0010));
        }
        return bars;
    }

    /** Five daily bars Monday through Friday at 12:00 UTC. */
    public static List<Bar> weekMonToFri(String symbol, LocalDate monday) {
        List<Bar> bars = new ArrayList<>(5);
        for (int d = 0; d < 5; d++) {
            Instant ts = monday.plusDays(d).atTime(12, 0).toInstant(ZoneOffset.UTC);
            bars.add(bar(symbol, ts, 1.1000 + d * 0.0010));
        }
        return bars;
    }

    public static List<Bar> repeat(String symbol, int count, double price) {
        List<Bar> bars = new ArrayList<>(count);
        Instant ts = Instant.parse("2024-06-03T12:00:00Z"); // Monday UTC (forex trading day)
        for (int i = 0; i < count; i++) {
            bars.add(bar(symbol, ts.plus(i, ChronoUnit.HOURS), price));
        }
        return bars;
    }

    private static Bar bar(String symbol, Instant timestamp, double price) {
        return new Bar(symbol, timestamp, price, price + 0.0005, price - 0.0005, price, 1_000);
    }
}
