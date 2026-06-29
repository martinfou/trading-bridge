package com.martinfou.trading.core.metrics;

import com.martinfou.trading.core.Trade;
import java.util.List;

/**
 * Profit Factor performance metric calculation.
 */
public final class ProfitFactor {

    private ProfitFactor() {}

    public static double of(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return 0.0;
        }
        double wins = 0.0;
        double losses = 0.0;
        for (Trade t : trades) {
            if (t.pnl() > 0) {
                wins += t.pnl();
            } else {
                losses += Math.abs(t.pnl());
            }
        }
        if (losses == 0.0) {
            return wins > 0 ? wins : 0.0;
        }
        return wins / losses;
    }
}
