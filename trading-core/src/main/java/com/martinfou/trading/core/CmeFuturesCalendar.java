package com.martinfou.trading.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Calendar for CME quarterly futures contracts (March H, June M, September U, December Z).
 * Contracts expire on the 3rd Friday of the contract month, with rollovers scheduled at T-10 calendar days.
 */
public final class CmeFuturesCalendar {

    public enum QuarterMonth {
        MARCH(3, 'H'),
        JUNE(6, 'M'),
        SEPTEMBER(9, 'U'),
        DECEMBER(12, 'Z');

        private final int monthValue;
        private final char code;

        QuarterMonth(int monthValue, char code) {
            this.monthValue = monthValue;
            this.code = code;
        }

        public int monthValue() {
            return monthValue;
        }

        public char code() {
            return code;
        }

        public static QuarterMonth fromMonth(int month) {
            for (QuarterMonth q : values()) {
                if (q.monthValue == month) return q;
            }
            throw new IllegalArgumentException("Not a quarterly CME month: " + month);
        }

        public static QuarterMonth fromCode(char code) {
            char upper = Character.toUpperCase(code);
            for (QuarterMonth q : values()) {
                if (q.code == upper) return q;
            }
            throw new IllegalArgumentException("Unknown CME month code: " + code);
        }
    }

    public record ContractSpec(
        String rootSymbol,
        int year,
        QuarterMonth quarterMonth,
        String contractCode,
        LocalDate expiryDate,
        LocalDate rolloverDate
    ) {}

    private CmeFuturesCalendar() {}

    /**
     * Calculates the 3rd Friday of the specified year and month.
     */
    public static LocalDate expiryDate(int year, int month) {
        LocalDate firstOfMonth = LocalDate.of(year, month, 1);
        LocalDate firstFriday = firstOfMonth.with(TemporalAdjusters.firstInMonth(DayOfWeek.FRIDAY));
        return firstFriday.plusWeeks(2); // 3rd Friday
    }

    /**
     * Rollover date scheduled at T-10 calendar days prior to contract expiry.
     */
    public static LocalDate rolloverDate(int year, int month) {
        return expiryDate(year, month).minusDays(10);
    }

    /**
     * Builds contract specification for a root symbol, year, and quarter month.
     */
    public static ContractSpec contractSpec(String rootSymbol, int year, QuarterMonth qm) {
        String cleanRoot = FuturesRegistry.normalizeSymbol(rootSymbol);
        int twoDigitYear = year % 100;
        String contractCode = String.format("%s%c%02d", cleanRoot, qm.code(), twoDigitYear);
        LocalDate expiry = expiryDate(year, qm.monthValue());
        LocalDate rollover = rolloverDate(year, qm.monthValue());
        return new ContractSpec(cleanRoot, year, qm, contractCode, expiry, rollover);
    }

    /**
     * Resolves the active front-month contract for a given trade date.
     */
    public static ContractSpec activeContract(String rootSymbol, LocalDate tradeDate) {
        int year = tradeDate.getYear();
        for (QuarterMonth qm : QuarterMonth.values()) {
            ContractSpec spec = contractSpec(rootSymbol, year, qm);
            if (!tradeDate.isAfter(spec.rolloverDate())) {
                return spec;
            }
        }
        // Past December rollover -> rolls into March of next year
        return contractSpec(rootSymbol, year + 1, QuarterMonth.MARCH);
    }

    /**
     * Resolves the subsequent quarterly contract following the provided spec.
     */
    public static ContractSpec nextContract(ContractSpec current) {
        QuarterMonth[] values = QuarterMonth.values();
        int idx = current.quarterMonth().ordinal();
        if (idx < values.length - 1) {
            return contractSpec(current.rootSymbol(), current.year(), values[idx + 1]);
        } else {
            return contractSpec(current.rootSymbol(), current.year() + 1, values[0]);
        }
    }

    /**
     * Checks whether rollover is due for the contract on the given trade date.
     */
    public static boolean isRolloverDue(ContractSpec spec, LocalDate currentDate) {
        return !currentDate.isBefore(spec.rolloverDate());
    }
}
