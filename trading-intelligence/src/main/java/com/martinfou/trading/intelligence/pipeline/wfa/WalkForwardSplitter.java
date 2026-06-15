package com.martinfou.trading.intelligence.pipeline.wfa;

import com.martinfou.trading.core.Bar;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits bars into rolling IS/OOS windows for Walk-Forward Analysis.
 * Purges boundary bars to prevent data leakage.
 */
public class WalkForwardSplitter {

    private static final Duration MONTH = Duration.ofDays(30);
    private static final Duration WEEK = Duration.ofDays(7);

    private final WfaConfig config;

    public WalkForwardSplitter(WfaConfig config) {
        this.config = config;
    }

    /**
     * Generate rolling IS/OOS windows from chronological bars.
     * Each window slides forward by `stepMonths`.
     */
    public List<WfaWindow> split(List<Bar> bars) {
        if (bars == null || bars.size() < 5000) return List.of();

        Instant first = bars.getFirst().timestamp();
        Instant last = bars.getLast().timestamp();

        long isDuration = MONTH.toMillis() * config.inSampleMonths();
        long oosDuration = WEEK.toMillis() * config.outOfSampleWeeks();
        long stepDuration = MONTH.toMillis() * config.stepMonths();

        List<WfaWindow> windows = new ArrayList<>();
        int windowIndex = 0;

        // Start after initial IS period
        Instant cursor = first.plusMillis(isDuration);

        while (cursor.plusMillis(oosDuration).isBefore(last)) {
            Instant isStart = cursor.minusMillis(isDuration);
            Instant isEnd = cursor;
            Instant oosStart = cursor;
            Instant oosEnd = cursor.plusMillis(oosDuration);

            List<Bar> isBars = extractRange(bars, isStart, isEnd);
            List<Bar> oosBars = extractRange(bars, oosStart, oosEnd);

            if (isBars.size() < 1000 || oosBars.size() < 100) {
                cursor = cursor.plusMillis(stepDuration);
                continue;
            }

            // Purge boundary bars to prevent data leakage
            isBars = purgeBoundary(isBars, true);
            oosBars = purgeBoundary(oosBars, false);

            windows.add(new WfaWindow(windowIndex++, isBars, oosBars,
                isStart, isEnd, oosStart, oosEnd));

            cursor = cursor.plusMillis(stepDuration);
        }

        return windows;
    }

    /** Extract bars within a time range. */
    private List<Bar> extractRange(List<Bar> bars, Instant start, Instant end) {
        int from = findIndex(bars, start);
        int to = findIndex(bars, end);
        if (from < 0) from = 0;
        if (to > bars.size()) to = bars.size();
        if (from >= to) return List.of();
        return new ArrayList<>(bars.subList(from, to));
    }

    /** Binary search for first bar >= timestamp. */
    private int findIndex(List<Bar> bars, Instant ts) {
        int lo = 0, hi = bars.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = bars.get(mid).timestamp().compareTo(ts);
            if (cmp < 0) lo = mid + 1;
            else if (cmp > 0) hi = mid - 1;
            else return mid;
        }
        return lo;
    }

    /**
     * Purge bars at the start (for OOS) or end (for IS) to prevent
     * look-ahead bias from stateful indicators near the boundary.
     */
    private List<Bar> purgeBoundary(List<Bar> bars, boolean atEnd) {
        if (bars.size() <= config.boundaryPurgeBars()) return bars;
        int purge = Math.min(config.boundaryPurgeBars(), bars.size() / 10);
        if (atEnd) {
            return new ArrayList<>(bars.subList(0, bars.size() - purge));
        } else {
            return new ArrayList<>(bars.subList(purge, bars.size()));
        }
    }
}
