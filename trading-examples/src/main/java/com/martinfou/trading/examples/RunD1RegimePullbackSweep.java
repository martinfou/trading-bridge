package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.D1RegimeEmaPullbackContinuation;

import java.util.List;

/**
 * Sweep paramétrique D1RegimeEmaPullbackContinuation — seuil régime × fenêtre.
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunD1RegimePullbackSweep GBP_USD 2006-2026
 */
public class RunD1RegimePullbackSweep {

    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "GBP_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();

        int[] windows = {16, 20, 24};
        double[] thresholds = {0.03, 0.04, 0.05, 0.06, 0.07};

        System.out.printf("%nSweep %s (%s) — seuil × fenêtre, AVEC coûts%n", symbol, yearSpec);
        System.out.printf("%-8s", "win\\thr");
        for (double t : thresholds) System.out.printf(" %8.2f", t);
        System.out.println();
        System.out.println("------------------------------------------------------------");

        for (int w : windows) {
            System.out.printf("%-8d", w);
            for (double t : thresholds) {
                var strat = new D1RegimeEmaPullbackContinuation("Sweep", symbol, w, t);
                BacktestResult r = RunContext.forStrategy(null, "Sweep", strat, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf(" %5.2f/%4d", r.profitFactor(), r.totalTrades());
            }
            System.out.println();
        }
    }
}
