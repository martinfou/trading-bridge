package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.util.List;

/**
 * HMM Regime Momentum — sweep on REGIME_PROB_MIN and sideways threshold.
 * Tests whether the near-zero trade count of the catalog HMM strategies
 * (11 trades / 20 years) is a threshold artifact or a dead signal.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunHmmSweep EUR_USD 2006-2026
 */
public class RunHmmSweep {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.printf("%n=== HMM sweep %s (%d bars) — coûts $0.07 + 0.01%% ===%n%n", symbol, bars.size());

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        double[] probMins = {0.55, 0.40, 0.30, 0.20, 0.15};
        double[] sideThreshs = {0.001, 0.0005, 0.002};

        for (double th : sideThreshs) {
            for (double pm : probMins) {
                String id = "HmmSweep_t" + th + "_p" + pm;
                var strat = new HmmRegimeSweepStrategy(id, symbol, pm, th, 80, 20);
                var res = RunContext.forStrategy(null, id, strat, symbol,
                    RunMode.BACKTEST, bars, capital, null, cost).run();
                System.out.printf("th=%.4f pmin=%.2f | trades=%d WR=%.1f%% PF=%.2f Net=$%.2f DD=%.2f%%%n",
                    th, pm,
                    res.totalTrades(),
                    res.winRatePct(),
                    res.profitFactor(),
                    res.totalPnl(),
                    res.maxDrawdownPct());
            }
        }
    }
}
