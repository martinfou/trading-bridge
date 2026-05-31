package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigSnapshotTest {

    @Test
    void defaultsNormalizeNullCostsToZero() {
        RunConfigSnapshot snapshot = baseSnapshot(null, null);
        assertEquals(0.0, snapshot.commissionPerTrade());
        assertEquals(0.0, snapshot.slippagePct());
        assertTrue(snapshot.executionCost().isZero());
        assertFalse(snapshot.toMap().containsKey("commissionPerTrade"));
    }

    @Test
    void toMap_includesNonZeroCosts() {
        RunConfigSnapshot snapshot = baseSnapshot(3.0, 0.00015);
        assertEquals(3.0, snapshot.toMap().get("commissionPerTrade"));
        assertEquals(0.00015, snapshot.toMap().get("slippagePct"));
    }

    @Test
    void hash_differsWhenCostsDiffer() {
        RunConfigSnapshot zero = baseSnapshot(null, null);
        RunConfigSnapshot withCost = baseSnapshot(5.0, 0.0001);
        assertNotEquals(zero.hash(), withCost.hash());
    }

    private static RunConfigSnapshot baseSnapshot(Double commission, Double slippage) {
        return new RunConfigSnapshot(
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "BACKTEST",
            "sample",
            100,
            null,
            100_000.0,
            commission,
            slippage,
            null);
    }
}
