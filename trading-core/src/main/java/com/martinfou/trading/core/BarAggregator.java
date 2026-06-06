package com.martinfou.trading.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Dynamically aggregates smaller timeframe bars (e.g., M1) into larger
 * timeframe candles (e.g., M30, H1, D1) in memory.
 */
public final class BarAggregator {

    private final String symbol;
    private final String targetTimeframe;
    private final int periodMinutes;

    private Instant currentPeriodStart = null;
    private double open = 0.0;
    private double high = Double.NEGATIVE_INFINITY;
    private double low = Double.POSITIVE_INFINITY;
    private double close = 0.0;
    private long volume = 0L;

    private Bar lastCompletedBar = null;

    public BarAggregator(String symbol, String targetTimeframe) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.targetTimeframe = Objects.requireNonNull(targetTimeframe, "targetTimeframe").toUpperCase();
        this.periodMinutes = parsePeriodMinutes(this.targetTimeframe);
    }

    public void add(Bar smallBar) {
        Instant barStart = getPeriodStart(smallBar.timestamp());

        // If we transition to a new period, complete the current one
        if (currentPeriodStart != null && !currentPeriodStart.equals(barStart)) {
            completePeriod();
        }

        if (currentPeriodStart == null) {
            currentPeriodStart = barStart;
            open = smallBar.open();
            high = smallBar.high();
            low = smallBar.low();
        } else {
            high = Math.max(high, smallBar.high());
            low = Math.min(low, smallBar.low());
        }

        close = smallBar.close();
        volume += smallBar.volume();
    }

    public boolean isNewPeriod(Bar smallBar) {
        if (currentPeriodStart == null) {
            return true;
        }
        return !currentPeriodStart.equals(getPeriodStart(smallBar.timestamp()));
    }

    public void completePeriod() {
        if (currentPeriodStart != null) {
            lastCompletedBar = new Bar(symbol, currentPeriodStart, open, high, low, close, volume);
            // Reset for next period
            currentPeriodStart = null;
            open = 0.0;
            high = Double.NEGATIVE_INFINITY;
            low = Double.POSITIVE_INFINITY;
            close = 0.0;
            volume = 0L;
        }
    }

    public Bar getInProgressBar() {
        if (currentPeriodStart == null) {
            return null;
        }
        return new Bar(symbol, currentPeriodStart, open, high, low, close, volume);
    }

    public Bar getLastCompletedBar() {
        return lastCompletedBar;
    }

    private Instant getPeriodStart(Instant timestamp) {
        if (targetTimeframe.startsWith("D")) {
            ZonedDateTime zdt = timestamp.atZone(ZoneOffset.UTC);
            return zdt.truncatedTo(ChronoUnit.DAYS).toInstant();
        }

        long epochSec = timestamp.getEpochSecond();
        long minutes = epochSec / 60;
        long roundedMinutes = (minutes / periodMinutes) * periodMinutes;
        return Instant.ofEpochSecond(roundedMinutes * 60);
    }

    private static int parsePeriodMinutes(String tf) {
        String upper = tf.toUpperCase();
        if (upper.startsWith("M")) {
            return Integer.parseInt(upper.substring(1));
        } else if (upper.startsWith("H")) {
            return Integer.parseInt(upper.substring(1)) * 60;
        } else if (upper.startsWith("D")) {
            return 24 * 60; // 1 day in minutes
        }
        throw new IllegalArgumentException("Unsupported timeframe format: " + tf);
    }
}
