package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStressConfigTest {

    @Test
    void stressCost_multipliesBaselineOrDefaults() {
        ExecutionStressConfig config = new ExecutionStressConfig(
            true, 3.0, 2.0, 0.0001, 5.0, 25.0, -40.0);

        BacktestExecutionCost fromZero = config.stressCost(BacktestExecutionCost.ZERO);
        assertEquals(10.0, fromZero.commissionPerTrade());
        assertEquals(0.0003, fromZero.slippagePct(), 1e-9);

        BacktestExecutionCost fromBaseline = config.stressCost(
            BacktestExecutionCost.ofCommissionAndSlippage(4.0, 0.0002));
        assertEquals(8.0, fromBaseline.commissionPerTrade());
        assertEquals(0.0006, fromBaseline.slippagePct(), 1e-9);
    }

    @Test
    void stressCostMap_matchesStressProfile() {
        ExecutionStressConfig config = ExecutionStressConfig.DEFAULT;
        assertTrue(config.stressCostMap(BacktestExecutionCost.ZERO).containsKey("slippagePct"));
    }
}
