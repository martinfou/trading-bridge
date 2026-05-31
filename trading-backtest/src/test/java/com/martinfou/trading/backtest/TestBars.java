package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Deterministic bar sequences for platform test strategies (no random data). */
public final class TestBars {

    public static final String DEFAULT_SYMBOL = "EUR_USD";
    private static final Instant BASE_TIME = Instant.parse("2012-06-01T12:00:00Z");

    private TestBars() {}

    /** OHLC rows: {open, high, low, close}. */
    public static List<Bar> ohlc(String symbol, double[][] rows) {
        var bars = new ArrayList<Bar>(rows.length);
        for (int i = 0; i < rows.length; i++) {
            double[] r = rows[i];
            bars.add(new Bar(symbol, BASE_TIME.plusSeconds(3600L * i), r[0], r[1], r[2], r[3], 1000));
        }
        return bars;
    }

    public static List<Bar> ohlc(double[][] rows) {
        return ohlc(DEFAULT_SYMBOL, rows);
    }

    public static List<Bar> flat(String symbol, int count, double price) {
        double[][] rows = new double[count][4];
        for (int i = 0; i < count; i++) {
            rows[i] = new double[] {price, price + 0.0010, price - 0.0010, price};
        }
        return ohlc(symbol, rows);
    }

    public static List<Bar> flat(int count, double price) {
        return flat(DEFAULT_SYMBOL, count, price);
    }

    /** Monotonic rising closes (deterministic fuel for SMA crossover smoke tests). */
    public static List<Bar> uptrend(int count, double startClose, double closeStep) {
        double[][] rows = new double[count][4];
        for (int i = 0; i < count; i++) {
            double close = startClose + closeStep * i;
            double open = i == 0 ? startClose : startClose + closeStep * (i - 1);
            rows[i] = new double[] {open, close + 0.0010, Math.min(open, close) - 0.0010, close};
        }
        return ohlc(rows);
    }
}
