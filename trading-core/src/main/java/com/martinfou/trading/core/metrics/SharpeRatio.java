package com.martinfou.trading.core.metrics;

import com.martinfou.trading.core.Trade;
import java.util.List;

/**
 * Sharpe Ratio performance metric calculation.
 */
public final class SharpeRatio {

    private SharpeRatio() {}

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
        for (Trade t : trades) {
            sumSq += Math.pow(t.pnl() - mean, 2);
        }
        double std = Math.sqrt(sumSq / (trades.size() - 1));
        if (std == 0.0) {
            return 0.0;
        }
        return mean / std;
    }
}
