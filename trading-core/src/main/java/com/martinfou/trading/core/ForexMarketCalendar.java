package com.martinfou.trading.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Spot FX is closed on Saturday and Sunday (UTC calendar days).
 * Used by the backtest engine to ignore weekend bars in historical series that
 * should not contain them (bad exports, synthetic tests).
 */
public final class ForexMarketCalendar {

    private ForexMarketCalendar() {}

    /** OANDA-style symbols ({@code EUR_USD}, {@code GBP_JPY}). */
    public static boolean isForexSymbol(String symbol) {
        return symbol != null && symbol.contains("_");
    }

    public static boolean isWeekendUtc(Instant instant) {
        DayOfWeek dow = instant.atZone(TimeConventions.UTC).getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    public static boolean isWeekendUtc(ZonedDateTime zdt) {
        DayOfWeek dow = zdt.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /** Whether this bar is processed for fills, strategy {@code onBar}, and SL/TP. */
    public static boolean isTradingBar(Bar bar) {
        if (!isForexSymbol(bar.symbol())) {
            return true;
        }
        return !isWeekendUtc(bar.timestamp());
    }
}
