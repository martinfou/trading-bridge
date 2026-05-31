package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestExecutionCostTest {

    @Test
    void zero_preservesEngineResults() {
        var bars = TestBars.flat(2, 1.1);
        BacktestResult baseline = new BacktestEngine(TestStrategies.noOp(), bars, 100_000.0).run();
        BacktestResult viaZero = BacktestExecutionCost.ZERO
            .configure(new BacktestEngine(TestStrategies.noOp(), bars, 100_000.0))
            .run();
        assertEquals(baseline.totalPnl(), viaZero.totalPnl());
    }

    @Test
    void ofCommissionAndSlippage_configuresEngine() {
        var bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015}
        });
        BacktestResult noCost = new BacktestEngine(TestStrategies.buyOnce(), bars, 100_000.0).run();
        BacktestResult withCost = BacktestExecutionCost.ofCommissionAndSlippage(5.0, 0.0001)
            .configure(new BacktestEngine(TestStrategies.buyOnce(), bars, 100_000.0))
            .run();

        assertTrue(withCost.totalPnl() < noCost.totalPnl());
        assertTrue(withCost.totalCommission() > 0 || withCost.totalSlippage() > 0);
    }

    @Test
    void toMap_omitsZeroFields() {
        assertTrue(BacktestExecutionCost.ZERO.toMap().isEmpty());
        var map = BacktestExecutionCost.ofCommissionAndSlippage(2.5, 0.0002).toMap();
        assertEquals(2.5, map.get("commissionPerTrade"));
        assertEquals(0.0002, map.get("slippagePct"));
        assertFalse(map.containsKey("commissionPct"));
    }
}
