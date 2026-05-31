package com.martinfou.trading.runtime;

import java.time.Instant;
import java.time.ZoneOffset;

/** Tracks intraday peak equity for drawdown calculation (Story 17.10). */
final class DailyDrawdownTracker {

    private Instant utcDayStart;
    private double peakEquity;

    void update(Instant timestamp, double equity) {
        Instant dayStart = timestamp.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        if (utcDayStart == null || !utcDayStart.equals(dayStart)) {
            utcDayStart = dayStart;
            peakEquity = equity;
        } else {
            peakEquity = Math.max(peakEquity, equity);
        }
    }

    double drawdownPct(double currentEquity) {
        if (peakEquity <= 0) {
            return 0.0;
        }
        double dd = (peakEquity - currentEquity) / peakEquity * 100.0;
        return Math.max(0.0, dd);
    }

    double peakEquity() {
        return peakEquity;
    }
}
