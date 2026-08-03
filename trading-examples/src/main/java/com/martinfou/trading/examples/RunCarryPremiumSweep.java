package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.CarryPremiumStrategy;

import java.util.List;

/**
 * Sweep paramétrique CarryPremium — robustesse ±20% sur EMA period.
 *
 * Teste EMA 160 / 200 / 240 (200 ± 20%) sur plusieurs paires carry.
 * Plateau = approuvé. Pic = curve fitting = REJECT.
 *
 * Run: java -cp ... com.martinfou.trading.examples.RunCarryPremiumSweep <Symbol> <YearRange>
 */
public class RunCarryPremiumSweep {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "AUD_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.println("== CarryPremium sweep EMA (avec coûts) ==");
        System.out.println("Symbol=" + symbol + " bars=" + bars.size() + " range=" + yearSpec);

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        int[] emas = {160, 200, 240};
        for (int ema : emas) {
            var strategy = new CarryPremiumStrategy("CarryPremium_EMA" + ema, symbol, ema);
            var ctx = RunContext.forStrategy(
                null, "CarryPremium_EMA" + ema, strategy, symbol,
                RunMode.BACKTEST, bars, capital, null, cost);
            var r = ctx.run();
            System.out.printf("EMA %3d | P&L %10.2f (%6.2f%%) | PF %5.2f | Trades %4d | WR %5.1f%% | DD %5.2f%% | Swap %9.2f%n",
                ema, r.totalPnl(), r.totalReturnPct(), r.profitFactor(),
                r.totalTrades(), r.winRatePct(), r.maxDrawdownPct(), r.totalSwap());
        }
    }
}
