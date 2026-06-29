package com.martinfou.trading.core.metrics;

import com.martinfou.trading.core.Trade;
import java.util.List;

/**
 * Calmar Ratio performance metric calculation.
 */
public final class CalmarRatio {

    private CalmarRatio() {}

    public static double of(List<Trade> trades) {
        double maxDd = MaxDrawdown.of(trades);
        if (maxDd == 0.0) {
            return 0.0;
        }

        double totalPnl = 0.0;
        for (Trade t : trades) {
            totalPnl += t.pnl();
        }
        double initialCapital = 50000.0;
        double annReturn = (totalPnl / initialCapital) * 100.0;
        return annReturn / maxDd;
    }
}
