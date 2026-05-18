package com.martinfou.trading.core;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Canonical time handling for Trading Bridge.
 * Storage and comparisons use UTC; {@link #DISPLAY_ZONE} is for human-facing output only.
 *
 * @see docs/specs.md §2.5
 */
public final class TimeConventions {

    public static final ZoneId UTC = ZoneOffset.UTC;
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("America/Toronto");

    private static final DateTimeFormatter DISPLAY_FMT =
        DateTimeFormatter.ofPattern("EEE dd MMM yyyy HH:mm z");

    private TimeConventions() {}

    public static Clock clock() {
        return Clock.systemUTC();
    }

    public static Instant now() {
        return Instant.now(clock());
    }

    /** OANDA v3 API timestamps are UTC (RFC3339, often suffixed with {@code Z}). */
    public static Instant parseOandaTimestamp(String time) {
        Objects.requireNonNull(time, "time");
        String t = time.trim();
        if (t.endsWith("Z") || t.contains("+") || hasExplicitOffset(t)) {
            return Instant.parse(t);
        }
        return LocalDateTime.parse(t.substring(0, Math.min(19, t.length())),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(UTC)
            .toInstant();
    }

    public static String toDisplayString(Instant instant) {
        if (instant == null) {
            return "n/a";
        }
        return DISPLAY_FMT.format(instant.atZone(DISPLAY_ZONE));
    }

    /**
     * Standard and StrategyQuant CSV timestamps are treated as UTC wall-clock values
     * (no offset in file). See {@link DataLoader}.
     */
    public static Instant csvLocalAsUtc(String isoLocalDateTime) {
        return LocalDateTime.parse(isoLocalDateTime.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atZone(UTC)
            .toInstant();
    }

    /**
     * Parses a CSV timestamp: UTC wall-clock by default, or RFC3339 when {@code Z} / offset present.
     */
    public static Instant parseCsvTimestamp(String isoDateTime) {
        Objects.requireNonNull(isoDateTime, "isoDateTime");
        String s = isoDateTime.trim();
        if (s.endsWith("Z") || s.contains("+") || hasExplicitOffset(s)) {
            return Instant.parse(s);
        }
        return csvLocalAsUtc(s);
    }

    private static boolean hasExplicitOffset(String t) {
        int tIdx = t.indexOf('T');
        if (tIdx < 0) {
            return false;
        }
        return t.substring(tIdx + 1).matches(".*[+-]\\d{2}:?\\d{2}$");
    }

    /** Converts a scheduled release in its publication timezone to UTC {@link Instant}. */
    public static Instant eventAt(int year, int month, int day, int hour, int minute, ZoneId releaseZone) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, releaseZone).toInstant();
    }
}
