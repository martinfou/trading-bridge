package com.martinfou.trading.strategies;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtrFuturesPositionSizerTest {

    @Test
    void testStopDistanceQuantizationMes() {
        // ATR = 12.33, Multiple = 1.5 -> Raw = 18.495 -> Snaps to 18.50 on MES ($0.25 tick)
        double stop = AtrFuturesPositionSizer.calculateStopDistance("MES", 12.33, 1.5);
        assertEquals(18.50, stop, 1e-6);

        // ATR = 8.11, Multiple = 2.0 -> Raw = 16.22 -> Snaps to 16.25
        double stop2 = AtrFuturesPositionSizer.calculateStopDistance("MES", 8.11, 2.0);
        assertEquals(16.25, stop2, 1e-6);
    }

    @Test
    void testPositionSizingMes() {
        // Account = $50,000, Risk = 1% ($500 risk budget)
        // Stop = 10.0 points on MES -> Dollar risk per contract = 10.0 * $5.0 = $50.00
        // Contracts = floor(500 / 50) = 10 contracts
        AtrFuturesPositionSizer.SizingResult result = AtrFuturesPositionSizer.calculatePositionSize(
            "MES", 50_000.0, 0.01, 10.0
        );

        assertEquals(10, result.contracts());
        assertEquals(10.0, result.stopDistancePoints(), 1e-6);
        assertEquals(500.0, result.riskAmountUsd(), 1e-6);
        assertEquals(12_000.0, result.requiredInitialMarginUsd(), 1e-6);
        assertTrue(result.marginFeasible());
    }

    @Test
    void testPositionSizingSmallAccountClamping() {
        // Small account $5,000, Risk = 1% ($50 budget), Stop = 20 points ($100 risk)
        // Floor(50/100) = 0 contracts, but account has enough for initial margin ($1200), so clamps to 1 contract
        AtrFuturesPositionSizer.SizingResult result = AtrFuturesPositionSizer.calculatePositionSize(
            "MES", 5_000.0, 0.01, 20.0
        );

        assertEquals(1, result.contracts());
        assertEquals(100.0, result.riskAmountUsd(), 1e-6);
        assertTrue(result.marginFeasible());
    }
}
