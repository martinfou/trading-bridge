package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.*;
import com.martinfou.trading.core.*;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.longterm.LtCrossMomentum;
import java.util.List;

/**
 * Validation runner for LtCrossMomentum — multi-asset, walk-forward.
 */
public class RunLtCrossMomentum {
    public static void main(String[] args) throws Exception {
        String[][] runs = {
            {"EUR_USD", "2010-2025"},
            {"EUR_USD", "2010-2018"},
            {"EUR_USD", "2019-2022"},
            {"EUR_USD", "2023-2025"},
            {"GBP_USD", "2010-2025"},
        };

        for (String[] run : runs) {
            String symbol = run[0];
            String period = run[1];
            try {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, period);
                List<Bar> bars = loaded.bars();
                if (bars.size() < 100) {
                    System.out.printf("%-10s %-12s -> NO_DATA%n", symbol, period);
                    continue;
                }

                Strategy strat = new LtCrossMomentum("LtCrossMomentum", symbol);
                BacktestResult result = RunContext.forStrategy(
                    null, null, strat, symbol, RunMode.BACKTEST, bars, 100000.0, null,
                    BacktestExecutionCost.ZERO
                ).run();

                String label = period.equals("2010-2025") ? "FULL" :
                    period.equals("2010-2018") ? "IS" :
                    period.equals("2019-2022") ? "OOS1" : "OOS2";

                System.out.printf("%-10s %-5s T=%5d PF=%.2f Sharpe=%.2f PnL=$%+8.2f DD=%.2f%% WR=%.1f%%%n",
                    symbol, label,
                    result.totalTrades(), result.profitFactor(), result.sharpeRatio(),
                    result.totalPnl(), result.maxDrawdownPct(),
                    result.winningTrades() * 100.0 / Math.max(1, result.totalTrades()));

            } catch (Exception e) {
                System.out.printf("%-10s %-12s ERROR: %s%n", symbol, period, e.getMessage());
            }
        }
    }
}
