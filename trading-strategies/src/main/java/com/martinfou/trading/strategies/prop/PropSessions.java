package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;
import com.martinfou.trading.core.TimeConventions;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/** UTC session and calendar helpers for prop strategies. */
public final class PropSessions {

    private PropSessions() {}

    public static ZonedDateTime utc(Bar bar) {
        return bar.timestamp().atZone(TimeConventions.UTC);
    }

    public static int hour(Bar bar) {
        return utc(bar).getHour();
    }

    public static int dayKey(Bar bar) {
        LocalDate d = utc(bar).toLocalDate();
        return d.getYear() * 1000 + d.getDayOfYear();
    }

    public static int weekKey(Bar bar) {
        LocalDate d = utc(bar).toLocalDate();
        return d.getYear() * 100 + d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    public static boolean inHourRange(Bar bar, int startInclusive, int endInclusive) {
        int h = hour(bar);
        if (startInclusive <= endInclusive) {
            return h >= startInclusive && h <= endInclusive;
        }
        return h >= startInclusive || h <= endInclusive;
    }

    public static boolean isMonday(Bar bar) {
        return utc(bar).getDayOfWeek() == DayOfWeek.MONDAY;
    }

    public static boolean isFriday(Bar bar) {
        return utc(bar).getDayOfWeek() == DayOfWeek.FRIDAY;
    }

    /** Previous UTC day's high/low from completed bars. */
    public static double[] previousDayHighLow(List<Bar> bars) {
        if (bars.size() < 2) return new double[] {Double.NaN, Double.NaN};
        int currentDay = dayKey(bars.getLast());
        int prevDay = -1;
        double hi = Double.NEGATIVE_INFINITY;
        double lo = Double.POSITIVE_INFINITY;
        for (int i = bars.size() - 2; i >= 0; i--) {
            int dk = dayKey(bars.get(i));
            if (dk == currentDay) continue;
            if (prevDay == -1) prevDay = dk;
            if (dk != prevDay) break;
            hi = Math.max(hi, bars.get(i).high());
            lo = Math.min(lo, bars.get(i).low());
        }
        if (prevDay == -1) return new double[] {Double.NaN, Double.NaN};
        return new double[] {hi, lo};
    }

    /** Friday close vs current bar open gap (pips). Positive = gap up. */
    public static double weekendGapPips(List<Bar> bars, String symbol) {
        if (bars.size() < 2 || !isMonday(bars.getLast())) return Double.NaN;
        Bar cur = bars.getLast();
        double pip = Indicators.pipSize(symbol);
        for (int i = bars.size() - 2; i >= 0; i--) {
            Bar b = bars.get(i);
            if (PropSessions.utc(b).getDayOfWeek() == DayOfWeek.FRIDAY) {
                return (cur.open() - b.close()) / pip;
            }
        }
        return Double.NaN;
    }
}
