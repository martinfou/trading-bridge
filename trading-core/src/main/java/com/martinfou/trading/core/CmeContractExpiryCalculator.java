package com.martinfou.trading.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Calculates CME quarterly futures expiration dates and rollover observation windows (Story 46.4).
 *
 * <p>CME Index Futures (`MES`, `MNQ`, `M2K`, `ES`, `NQ`) quarterly expiration cycle:
 * <ul>
 *   <li>March (H)</li>
 *   <li>June (M)</li>
 *   <li>September (U)</li>
 *   <li>December (Z)</li>
 * </ul>
 * Expiration occurs on the third Friday of the contract month.</p>
 */
public final class CmeContractExpiryCalculator {

    public static final List<Month> QUARTERLY_MONTHS = List.of(
        Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER
    );

    private CmeContractExpiryCalculator() {}

    /**
     * Calculates the third Friday of a given year and month.
     */
    public static LocalDate getThirdFriday(int year, Month month) {
        LocalDate firstOfMonth = LocalDate.of(year, month, 1);
        LocalDate firstFriday = firstOfMonth.with(TemporalAdjusters.firstInMonth(DayOfWeek.FRIDAY));
        return firstFriday.plusWeeks(2);
    }

    /**
     * Finds the nearest upcoming quarterly contract expiration on or after the given date.
     */
    public static LocalDate getNextExpiration(LocalDate date) {
        if (date == null) date = LocalDate.now(ZoneId.of("America/New_York"));
        int year = date.getYear();

        for (int y = year; y <= year + 1; y++) {
            for (Month m : QUARTERLY_MONTHS) {
                LocalDate expiry = getThirdFriday(y, m);
                if (!expiry.isBefore(date)) {
                    return expiry;
                }
            }
        }
        return getThirdFriday(year + 1, Month.MARCH);
    }

    /**
     * Determines whether the given date falls within the rollover window (8 calendar days before expiration).
     */
    public static boolean isRollWindow(LocalDate date) {
        LocalDate nextExpiry = getNextExpiration(date);
        LocalDate rollWindowStart = nextExpiry.minusDays(8);
        return !date.isBefore(rollWindowStart) && !date.isAfter(nextExpiry);
    }

    /**
     * Returns the quarterly contract code (H, M, U, Z) for the given month.
     */
    public static String getMonthCode(Month month) {
        return switch (month) {
            case MARCH -> "H";
            case JUNE -> "M";
            case SEPTEMBER -> "U";
            case DECEMBER -> "Z";
            default -> "H";
        };
    }
}
