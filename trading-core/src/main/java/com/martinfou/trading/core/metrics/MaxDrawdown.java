package com.martinfou.trading.core.metrics;

import com.martinfou.trading.core.Trade;
import java.util.ArrayList;
import java.util.List;

/**
 * Maximum Drawdown percentage performance metric calculation.
 */
public final class MaxDrawdown {

    private MaxDrawdown() {}

    public static double of(List<Trade> trades) {
        return of(trades, 50000.0);
    }

    public static double of(List<Trade> trades, double initialCapital) {
        if (trades == null || trades.isEmpty()) {
            return 0.0;
        }
        List<Double> equityCurve = new ArrayList<>();
        double current = initialCapital;
        equityCurve.add(current);
        for (Trade t : trades) {
            current += t.pnl();
            equityCurve.add(current);
        }
        double peak = Double.NEGATIVE_INFINITY;
        double maxDd = 0.0;
        for (double eq : equityCurve) {
            if (eq > peak) {
                peak = eq;
            }
            double dd = (peak - eq) / peak;
            if (dd > maxDd) {
                maxDd = dd;
            }
        }
        return maxDd * 100.0; // returns in percentage (e.g. 15.0 for 15%)
    }
}
