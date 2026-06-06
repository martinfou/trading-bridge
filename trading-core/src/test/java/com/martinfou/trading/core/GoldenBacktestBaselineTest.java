package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void metricMismatches_emptyWhenSnapshotMatchesProfile() {
        var p = GoldenBacktestBaseline.CI_SUBSET;
        var snapshot = new GoldenBacktestBaseline.MetricSnapshot(
            p.bars(), p.trades(), p.returnPct(), p.totalPnl(), p.maxDrawdownPct(),
            GoldenBacktestBaseline.INITIAL_CAPITAL + p.totalPnl());
        assertTrue(GoldenBacktestBaseline.metricMismatches(
            snapshot, p, GoldenBacktestBaseline.INITIAL_CAPITAL).isEmpty());
    }

    @Test
    void metricMismatches_reportsTradeDrift() {
        var p = GoldenBacktestBaseline.CI_SUBSET;
        var snapshot = new GoldenBacktestBaseline.MetricSnapshot(
            p.bars(), p.trades() + 1, p.returnPct(), p.totalPnl(), p.maxDrawdownPct(),
            GoldenBacktestBaseline.INITIAL_CAPITAL + p.totalPnl());
        List<String> errors = GoldenBacktestBaseline.metricMismatches(
            snapshot, p, GoldenBacktestBaseline.INITIAL_CAPITAL);
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("trade count"));
    }
}
