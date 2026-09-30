package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.*;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;
import com.martinfou.trading.strategies.creative.GoldFridayWindowStrategy;

import java.util.List;

/** Probe — timestamps entrée/sortie réels des trades GoldWeekdayEffect FRI vs GoldFridayWindow. */
public class GoldExitProbe {
    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        var bars = HistoricalDataLoader.loadFromArgs("XAU_USD", "XAU_USD", "2018").bars();

        BacktestResult r1 = RunContext.forStrategy(null, "GoldWeekdayEffect", 
            new GoldWeekdayEffectStrategy("GoldWeekdayEffect", "XAU_USD", false, true), "XAU_USD",
            RunMode.BACKTEST, bars, 50_000, null, cost).run();
        System.out.println("=== GoldWeekdayEffect FRI (2018) — 5 premiers trades ===");
        int c = 0;
        for (Trade t : r1.trades()) {
            System.out.printf("  %s -> %s  pnl=%+.2f%n", t.entryTime(), t.exitTime(), t.pnl());
            if (++c >= 5) break;
        }

        BacktestResult r2 = RunContext.forStrategy(null, "GoldFridayWindow",
            new GoldFridayWindowStrategy("GoldFridayWindow", "XAU_USD", 0, 21), "XAU_USD",
            RunMode.BACKTEST, bars, 50_000, null, cost).run();
        System.out.println("\n=== GoldFridayWindow [00:21) (2018) — 5 premiers trades ===");
        c = 0;
        for (Trade t : r2.trades()) {
            System.out.printf("  %s -> %s  pnl=%+.2f%n", t.entryTime(), t.exitTime(), t.pnl());
            if (++c >= 5) break;
        }
        System.out.println("\nTrade class fields: entry/exit are Trade accessors");
    }
}
