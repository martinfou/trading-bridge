package com.martinfou.trading.intelligence.time;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/** UTC week boundaries for weekly strategy builder. */
public final class WeekBounds {

    private WeekBounds() {}

    /**
     * Monday UTC of the calendar week immediately after the week containing {@code reference}.
     * Example: Friday 2026-06-06 → weekStart 2026-06-09.
     */
    public static LocalDate nextWeekMonday(LocalDate reference) {
        if (reference.getDayOfWeek() == DayOfWeek.MONDAY) {
            return reference;
        }
        return reference.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    public static LocalDate nextWeekMonday(Clock clock) {
        return nextWeekMonday(LocalDate.now(clock));
    }

    /** ISO week id, e.g. {@code 2026-W24}. */
    public static String weekId(LocalDate weekStartMonday) {
        int week = weekStartMonday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = weekStartMonday.get(IsoFields.WEEK_BASED_YEAR);
        return year + "-W" + String.format("%02d", week);
    }

    public static String weekId(Clock clock) {
        return weekId(nextWeekMonday(clock));
    }

    /** Monday UTC for an ISO week id such as {@code 2026-W24}. */
    public static LocalDate parseWeekStart(String weekId) {
        if (weekId == null || weekId.isBlank()) {
            throw new IllegalArgumentException("weekId must not be blank");
        }
        try {
            return LocalDate.parse(weekId + "-1", DateTimeFormatter.ISO_WEEK_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid weekId: " + weekId, ex);
        }
    }

    /** Monday 00:00 UTC for the ISO week containing {@code weekStartMonday}. */
    public static Instant validFrom(LocalDate weekStartMonday) {
        return weekStartMonday.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Friday 21:00 UTC of the trading week starting {@code weekStartMonday}. */
    public static Instant validUntil(LocalDate weekStartMonday) {
        LocalDate friday = weekStartMonday.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        return friday.atTime(LocalTime.of(21, 0)).toInstant(ZoneOffset.UTC);
    }
}
