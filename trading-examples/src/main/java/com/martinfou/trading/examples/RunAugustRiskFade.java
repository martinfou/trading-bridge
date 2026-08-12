package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.AugustRiskFadeStrategy;

import java.util.List;

/**
 * RunAugustRiskFade — Backtest AugustRiskFadeStrategy (SELL August on risk pairs)
 * AVEC coûts (commission $0.07 + slippage 0.01%) — jamais sans coûts.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunAugustRiskFade AUD_USD 2006-2026
 */
public class RunAugustRiskFade {

    public static void main(String[] args) throws Exception {
        String[] symbols = args.length > 0
            ? args[0].split(",")
            : new String[]{"AUD_USD", "NZD_USD", "GBP_USD"};
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        for (String symbol : symbols) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
            List<Bar> bars = loaded.bars();
            System.out.printf("%n=== %s (%d bars) — AugustRiskFade AVEC coûts $0.07 + 0.01%% slippage ===%n",
                symbol, bars.size());

            var strategy = new AugustRiskFadeStrategy("AugustRiskFade", symbol);
            RunContext.forStrategy(null, "AugustRiskFade", strategy, symbol,
                RunMode.BACKTEST, bars, capital, null, cost).run().printSummary();
        }
    }
}
