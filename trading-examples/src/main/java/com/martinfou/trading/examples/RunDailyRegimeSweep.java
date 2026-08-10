package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.DailyRegimeMomentum;

import java.util.List;

/**
 * Sweep paramétrique DailyRegimeMomentum — seuil ±20% et fenêtre ±20%.
 * Test anti-curve-fitting : plateau = robuste, pic = suspect.
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunDailyRegimeSweep EUR_USD 2006-2026
 */
public class RunDailyRegimeSweep {

    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.printf("%n=== DailyRegimeMomentum SWEEP %s — coûts $0.07 + 0.01%% slip ===%n%n", symbol);
        System.out.printf("%-16s %-16s %6s %7s %6s %7s %10s%n", "WINDOW", "THRESHOLD", "PF", "TRADES", "WR%", "DD%", "NET$");
        System.out.println("--------------------------------------------------------------------------------");

        int[] windows = {16, 20, 24};          // ±20% around 20
        double[] thresholds = {0.04, 0.05, 0.06}; // ±20% around 0.05

        for (int w : windows) {
            for (double t : thresholds) {
                var strat = new DailyRegimeMomentum("Sweep", symbol, w, t);
                BacktestResult r = RunContext.forStrategy(null, "Sweep", strat, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-16d %-16.2f %6.2f %7d %6.1f %7.2f %10.2f%n",
                    w, t, r.profitFactor(), r.totalTrades(), r.winRatePct(),
                    r.maxDrawdownPct(), r.totalPnl());
            }
        }
    }
}
