package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.util.List;

public class VerifyLtRSI3Momentum {
    public static void main(String[] args) throws Exception {
        System.out.println("Loading EUR_USD H1 data...");
        var loaded = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", "2022-2023");
        List<Bar> bars = loaded.bars();
        System.out.println("Loaded " + bars.size() + " bars.");

        LtRSI3Momentum strat = new LtRSI3Momentum("RSI3Mom", "EUR_USD");
        BacktestEngine engine = new BacktestEngine(strat, bars, 10000.0);
        BacktestExecutionCost.OANDA_SPREAD.configure(engine);

        BacktestResult res = engine.run();

        System.out.println("Total Trades: " + res.totalTrades());
        for (int i = 0; i < Math.min(10, res.trades().size()); i++) {
            Trade t = res.trades().get(i);
            System.out.println(String.format("Trade %d: %s Entry @ %s price=%.5f Exit @ %s price=%.5f PNL=%.2f",
                (i+1), t.side(), t.entryTime(), t.entryPrice(), t.exitTime(), t.exitPrice(), t.pnl()));
        }
    }
}
