package com.martinfou.trading.runtime;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Determines whether the market is open or closed for a given symbol and execution label.
 * Align calculations on Eastern Time zone (America/Toronto).
 */
public final class MarketSessionResolver {

    private static final ZoneId EASTERN_ZONE = ZoneId.of("America/Toronto");

    private MarketSessionResolver() {}

    public enum MarketType {
        FOREX, STOCKS, FUTURES
    }

    public static MarketType classify(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return MarketType.FOREX;
        }
        String s = symbol.trim().toUpperCase();
        if (s.startsWith("/") || s.matches("^(ES|NQ|YM|RTY|CL|GC)[A-Z0-9].*")) {
            return MarketType.FUTURES;
        }
        if (s.contains("_") || (s.length() == 6 && isCommonForexPair(s))) {
            return MarketType.FOREX;
        }
        return MarketType.STOCKS;
    }

    private static boolean isCommonForexPair(String s) {
        String base = s.substring(0, 3);
        String quote = s.substring(3, 6);
        return isCurrency(base) && isCurrency(quote);
    }

    private static boolean isCurrency(String c) {
        return List.of("EUR", "USD", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "HKD", "SGD").contains(c);
    }

    public static boolean isClosed(String symbol, String executionLabel, Instant now) {
        ExecutionLabel label = ExecutionLabel.parse(executionLabel);
        return isClosed(symbol, label, now);
    }

    public static boolean isClosed(String symbol, ExecutionLabel label, Instant now) {
        MarketType type = classify(symbol);
        ZonedDateTime est = now.atZone(EASTERN_ZONE);
        DayOfWeek day = est.getDayOfWeek();
        int hour = est.getHour();
        int minute = est.getMinute();

        return switch (type) {
            case FOREX -> {
                // Forex Close: Friday 17:00 EST to Sunday 17:00 EST
                if (day == DayOfWeek.SATURDAY) {
                    yield true;
                }
                if (day == DayOfWeek.FRIDAY && hour >= 17) {
                    yield true;
                }
                if (day == DayOfWeek.SUNDAY && hour < 17) {
                    yield true;
                }
                yield false;
            }
            case STOCKS -> {
                // US Stocks Close: Saturday & Sunday, or Monday-Friday outside 09:30 EST to 16:00 EST
                if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
                    yield true;
                }
                int minutesSinceMidnight = hour * 60 + minute;
                int openTime = 9 * 60 + 30; // 09:30
                int closeTime = 16 * 60; // 16:00
                yield minutesSinceMidnight < openTime || minutesSinceMidnight >= closeTime;
            }
            case FUTURES -> {
                // Futures Close: Friday 17:00 EST to Sunday 18:00 EST,
                // and daily Monday-Thursday from 17:00 to 18:00 EST (maintenance hour)
                if (day == DayOfWeek.SATURDAY) {
                    yield true;
                }
                if (day == DayOfWeek.FRIDAY && hour >= 17) {
                    yield true;
                }
                if (day == DayOfWeek.SUNDAY && hour < 18) {
                    yield true;
                }
                // Monday-Thursday maintenance hour: 17:00 to 18:00
                if ((day == DayOfWeek.MONDAY || day == DayOfWeek.TUESDAY || day == DayOfWeek.WEDNESDAY || day == DayOfWeek.THURSDAY)
                        && hour == 17) {
                    yield true;
                }
                yield false;
            }
        };
    }
}
