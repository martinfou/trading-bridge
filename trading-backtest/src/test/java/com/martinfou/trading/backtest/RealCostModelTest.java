package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the broker-anchored conversion in RealCostModel:
 * financing annual fraction → pips/day, and the pip-size convention per instrument.
 */
class RealCostModelTest {

    @Test
    void audUsdAnchorMatchesBroker() {
        // AUD_USD longRate = -0.0040 (-0.40 %/yr), mid 0.692945, pip 0.0001
        // -0.0040 × 0.692945 / (0.0001 × 365) = -0.0759 ≈ -0.08 pips/day (FEE-AUDIT.md §4).
        double pips = RealCostModel.swapPipsPerDay(-0.0040, 0.692945, "AUD_USD");
        assertEquals(-0.076, pips, 0.002);
    }

    @Test
    void pipSizeConvention() {
        assertEquals(0.0001, RealCostModel.pipSize("EUR_USD"), 1e-9);
        assertEquals(0.01, RealCostModel.pipSize("GBP_JPY"), 1e-9);
        assertEquals(0.01, RealCostModel.pipSize("XAU_USD"), 1e-9);
    }

    @Test
    void goldSwapIsLargeNotSmall() {
        // Gold financing ≈ -5.7 %/yr: the corrected value is ~-65 pips/day, not -2.0.
        double pips = RealCostModel.swapPipsPerDay(-0.0569, 4182.07, "XAU_USD");
        assertTrue(Math.abs(pips) > 60, "gold swap should be ~65 pips/day, got " + pips);
    }

    @Test
    void costForHasZeroCommissionAndMeasuredHalfSpread() {
        BacktestExecutionCost c = RealCostModel.costFor("EUR_USD", 1.12456);
        assertEquals(0.0, c.commissionPerTrade(), 1e-9);
        assertEquals(0.00008, c.slippageFixed(), 1e-12);   // half-spread per leg
        assertTrue(c.stopSlippagePct() > 0);               // SL exits pay the half-spread too
    }
}
