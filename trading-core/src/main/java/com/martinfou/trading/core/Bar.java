package com.martinfou.trading.core;

import java.time.Instant;
import java.util.Objects;

public class Bar {
    private final String symbol;
    private final Instant timestamp;
    private final double open, high, low, close;
    private final long volume;

    public Bar(String symbol, Instant timestamp, double open, double high, double low, double close, long volume) {
        this.symbol = symbol;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String symbol() { return symbol; }
    public Instant timestamp() { return timestamp; }
    public double open() { return open; }
    public double high() { return high; }
    public double low() { return low; }
    public double close() { return close; }
    public long volume() { return volume; }

    @Override
    public String toString() {
        return String.format("%s %s O:%.5f H:%.5f L:%.5f C:%.5f V:%d",
            symbol, TimeConventions.toDisplayString(timestamp), open, high, low, close, volume);
    }
}
