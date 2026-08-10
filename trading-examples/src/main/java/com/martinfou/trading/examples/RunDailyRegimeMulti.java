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
 * Multi-paires DailyRegimeMomentum AVEC coûts — résumé compact.
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunDailyRegimeMulti 2006-2026
 */
public class RunDailyRegimeMulti {

    static final double CAPITAL = 50_000;
    static final String[] PAIRS = {"EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD", "USD_CAD", "NZD_USD", "USD_CHF", "GBP_JPY"};

    public static void main(String[] args) throws Exception {
        String yearSpec = args.length > 0 ? args[0] : "2006-2026";
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.printf("%n%-9s %6s %7s %6s %7s %10s%n", "PAIR", "PF", "TRADES", "WR%", "DD%", "NET$");
        System.out.println("------------------------------------------------------------");

        for (String symbol : PAIRS) {
            try {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
                List<Bar> bars = loaded.bars();
                var strat = new DailyRegimeMomentum("DailyRegimeMomentum", symbol);
                BacktestResult r = RunContext.forStrategy(null, "DailyRegimeMomentum", strat, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-9s %6.2f %7d %6.1f %7.2f %10.2f%n",
                    symbol, r.profitFactor(), r.totalTrades(), r.winRatePct(),
                    r.maxDrawdownPct(), r.totalPnl());
            } catch (Exception e) {
                System.out.printf("%-9s ERROR: %s%n", symbol, e.getMessage());
            }
        }
    }
}
