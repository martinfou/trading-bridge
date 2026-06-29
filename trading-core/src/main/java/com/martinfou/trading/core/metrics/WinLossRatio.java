package com.martinfou.trading.core.metrics;

import com.martinfou.trading.core.Trade;
import java.util.List;

/**
 * Win Rate performance metric calculation.
 */
public final class WinLossRatio {

    private WinLossRatio() {}

    public static double of(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return 0.0;
        }
        long wins = trades.stream().filter(t -> t.pnl() > 0).count();
        return (double) wins / trades.size();
    }
}
