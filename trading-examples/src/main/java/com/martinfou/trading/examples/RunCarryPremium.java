package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.CarryPremiumStrategy;

import java.util.List;

/**
 * CarryPremiumStrategy — backtest AVEC coûts (commission $0.07 + slippage 0.01%).
 *
 * Teste le concept carry trade : le swap est crédité via closeOnly() sur la durée
 * de détention. Le PF affiché par le moteur est calculé SANS swap (tradePnlList) —
 * le Net P&L inclut le swap. Les deux sont affichés dans le summary.
 *
 * Run: java -cp ... com.martinfou.trading.examples.RunCarryPremium <Symbol> <YearRange>
 * Example: ... RunCarryPremium AUD_USD 2006-2026
 */
public class RunCarryPremium {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "AUD_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.println("== CarryPremium backtest (avec coûts) ==");
        System.out.println("Symbol=" + symbol + " bars=" + bars.size() + " range=" + yearSpec
            + " source=" + loaded.source());
        System.out.println("First bar: " + bars.get(0).timestamp()
            + "  Last bar: " + bars.get(bars.size() - 1).timestamp());

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        var strategy = new CarryPremiumStrategy("CarryPremium_" + symbol, symbol);
        var ctx = RunContext.forStrategy(
            null, "CarryPremium_" + symbol, strategy, symbol,
            RunMode.BACKTEST, bars, capital, null, cost);
        ctx.run().printSummary();
    }
}
