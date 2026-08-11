package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.D1RegimeEmaPullbackContinuation;
import com.martinfou.trading.strategies.prop.EmaPullbackContinuationStrategy;

import java.util.List;

/**
 * Multi-paires : baseline EmaPullbackContinuation (prop) vs variation
 * D1RegimeEmaPullbackContinuation — AVEC coûts ($0.07 + 0.01% slippage).
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunD1RegimePullbackMulti 2006-2026
 */
public class RunD1RegimePullbackMulti {

    static final double CAPITAL = 50_000;
    static final String[] PAIRS = {"EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD", "USD_CAD", "NZD_USD", "USD_CHF", "GBP_JPY"};

    public static void main(String[] args) throws Exception {
        String yearSpec = args.length > 0 ? args[0] : "2006-2026";
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.printf("%n%-9s | %-34s | %-34s%n", "PAIR", "BASELINE EmaPullback", "VARIATION D1Regime");
        System.out.printf("%-9s | %6s %6s %6s %7s %9s | %6s %6s %6s %7s %9s%n",
            "", "PF", "TR", "WR%", "DD%", "NET$", "PF", "TR", "WR%", "DD%", "NET$");
        System.out.println("--------------------------------------------------------------------------------------------------------");

        for (String symbol : PAIRS) {
            try {
                var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
                List<Bar> bars = loaded.bars();

                var base = new EmaPullbackContinuationStrategy(symbol);
                BacktestResult rb = RunContext.forStrategy(null, "Prop_EMAPullback", base, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();

                var varStrat = new D1RegimeEmaPullbackContinuation(symbol);
                BacktestResult rv = RunContext.forStrategy(null, "D1RegimeEmaPullbackContinuation", varStrat, symbol,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();

                System.out.printf("%-9s | %6.2f %6d %6.1f %7.2f %9.2f | %6.2f %6d %6.1f %7.2f %9.2f%n",
                    symbol,
                    rb.profitFactor(), rb.totalTrades(), rb.winRatePct(), rb.maxDrawdownPct(), rb.totalPnl(),
                    rv.profitFactor(), rv.totalTrades(), rv.winRatePct(), rv.maxDrawdownPct(), rv.totalPnl());
            } catch (Exception e) {
                System.out.printf("%-9s ERROR: %s%n", symbol, e.getMessage());
            }
        }
    }
}
