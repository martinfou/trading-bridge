package com.martinfou.trading.core;

import java.time.Instant;
import java.util.Objects;

public record Bar(String symbol, Instant timestamp, double open, double high, double low, double close, long volume) {
    public Bar {
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public String toString() {
        return String.format("%s %s O:%.5f H:%.5f L:%.5f C:%.5f V:%d",
            symbol, TimeConventions.toDisplayString(timestamp), open, high, low, close, volume);
    }
}
