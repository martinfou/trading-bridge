package com.martinfou.trading.core.metrics;

import com.martinfou.trading.core.Trade;
import java.util.List;

/**
 * Sortino Ratio performance metric calculation.
 */
public final class SortinoRatio {

    private SortinoRatio() {}

    public static double of(List<Trade> trades) {
        if (trades == null || trades.size() < 2) {
            return 0.0;
        }
        double sum = 0.0;
        for (Trade t : trades) {
            sum += t.pnl();
        }
        double mean = sum / trades.size();

        double sumSq = 0.0;
        int count = 0;
        for (Trade t : trades) {
            if (t.pnl() < 0.0) {
                sumSq += Math.pow(t.pnl(), 2);
                count++;
            }
        }
        if (count < 2) {
            return 0.0;
        }
        double downsideStd = Math.sqrt(sumSq / (count - 1));
        if (downsideStd == 0.0) {
            return 0.0;
        }
        return mean / downsideStd;
    }
}
