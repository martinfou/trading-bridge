package com.martinfou.trading.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Dynamically aggregates smaller timeframe bars (e.g., M1) into larger
 * timeframe candles (e.g., M5, M15, M30, H1, H4, D1, W1) in memory.
 */
public final class BarAggregator {

    private final String symbol;
    private final String targetTimeframe;
    private final int periodMinutes;
    private final ZoneId sessionZone;
    private final int rolloverHour;

    private Instant currentPeriodStart = null;
    private double open = 0.0;
    private double high = Double.NEGATIVE_INFINITY;
    private double low = Double.POSITIVE_INFINITY;
    private double close = 0.0;
    private long volume = 0L;

    private Bar lastCompletedBar = null;

    /**
     * Uses the instrument's natural session boundary for D1/W1: CME futures roll at 18:00 New York,
     * FX at 17:00 New York, anything else at 00:00 UTC. Intraday sizes are unaffected.
     */
    public BarAggregator(String symbol, String targetTimeframe) {
        this(symbol, targetTimeframe, defaultZone(symbol), defaultRolloverHour(symbol));
    }

    /** Explicit session boundary for D1/W1: a trading day starts at {@code rolloverHour} local time in {@code zone}. */
    public BarAggregator(String symbol, String targetTimeframe, ZoneId zone, int rolloverHour) {
        this.sessionZone = Objects.requireNonNull(zone, "zone");
        this.rolloverHour = rolloverHour;
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

    public Optional<Bar> getInProgressBar() {
        if (currentPeriodStart == null) {
            return Optional.empty();
        }
        return Optional.of(new Bar(symbol, currentPeriodStart, open, high, low, close, volume));
    }

    public Optional<Bar> getLastCompletedBar() {
        return Optional.ofNullable(lastCompletedBar);
    }

    private static ZoneId defaultZone(String symbol) {
        return usesNewYorkSession(symbol) ? ZoneId.of("America/New_York") : ZoneOffset.UTC;
    }

    private static int defaultRolloverHour(String symbol) {
        if (FuturesRegistry.isFutures(symbol)) {
            return 18;
        }
        return usesNewYorkSession(symbol) ? 17 : 0;
    }

    private static boolean usesNewYorkSession(String symbol) {
        return FuturesRegistry.isFutures(symbol) || symbol.matches("[A-Za-z]{3}[_/][A-Za-z]{3}");
    }

    /** Local date of the trading day containing the timestamp (a session opening at 18:00 belongs to the next date). */
    private LocalDate tradingDate(Instant timestamp) {
        return timestamp.atZone(sessionZone).toLocalDateTime().plusHours(24 - rolloverHour).toLocalDate();
    }

    private Instant tradingDayStart(LocalDate date) {
        return date.atStartOfDay().minusHours(24 - rolloverHour).atZone(sessionZone).toInstant();
    }

    private Instant getPeriodStart(Instant timestamp) {
        if (targetTimeframe.startsWith("D")) {
            return tradingDayStart(tradingDate(timestamp));
        }

        if (targetTimeframe.startsWith("W")) {
            LocalDate monday = tradingDate(timestamp)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            return tradingDayStart(monday);
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
        } else if (upper.startsWith("W")) {
            return 7 * 24 * 60; // 1 week in minutes
        }
        throw new IllegalArgumentException("Unsupported timeframe format: " + tf);
    }
}
