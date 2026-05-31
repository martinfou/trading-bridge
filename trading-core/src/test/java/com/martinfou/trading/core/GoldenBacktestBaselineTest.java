package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldenBacktestBaselineTest {

    @Test
    void ciSubset_pnlMatchesReturnPct() {
        var p = GoldenBacktestBaseline.CI_SUBSET;
        assertEquals(p.totalPnl(), p.returnPct() / 100.0 * GoldenBacktestBaseline.INITIAL_CAPITAL, 1e-9);
    }

    @Test
    void eurUsd2012_pnlMatchesReturnPct() {
        var p = GoldenBacktestBaseline.EUR_USD_2012;
        assertEquals(p.totalPnl(), p.returnPct() / 100.0 * GoldenBacktestBaseline.INITIAL_CAPITAL, 1e-9);
    }
}
