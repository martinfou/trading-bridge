package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the broker-anchored conversion in RealCostModel:
 * financing annual fraction → pips/day, and the pip-size convention per instrument.
 * Also locks the measured half-spread fallback rule (refuse, not understate).
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
    void goldSwapUsesPeriodAverageMidNotTodaySpot() {
        // Gold financing ≈ -5.7 %/yr at the 2010-2025 average mid 1664 → ~-25.9 pips/day.
        // At today's 4182 the same formula gives ~-65.2, overstating 15-year carry ~2.5×.
        double atPeriodAvg = RealCostModel.swapPipsPerDay(-0.0569, RealCostModel.GOLD_SWAP_MID, "XAU_USD");
        double atToday = RealCostModel.swapPipsPerDay(-0.0569, 4182.07, "XAU_USD");
        assertTrue(Math.abs(atPeriodAvg) > 24 && Math.abs(atPeriodAvg) < 27, "period-avg gold swap ~-25.9, got " + atPeriodAvg);
        assertTrue(atToday < atPeriodAvg, "today's spot must be more negative than the period average");
    }

    @Test
    void audUsdHalfSpreadIsMeasured() {
        // AUD_USD median 1.30 pip → 0.65 pip per leg = 0.000065 (FEE-AUDIT §2). Was missing → defaulted to 1.0 pip.
        assertEquals(0.000065, RealCostModel.halfSpread("AUD_USD"), 1e-12);
    }

    @Test
    void halfSpreadNormalizesSymbol() {
        assertEquals(0.00008, RealCostModel.halfSpread("EURUSD"), 1e-12);     // no underscore
        assertEquals(0.00008, RealCostModel.halfSpread("eur_usd"), 1e-12);    // lowercase + underscore
        assertEquals(0.0080, RealCostModel.halfSpread("USD/JPY"), 1e-12);     // slash form
    }

    @Test
    void halfSpreadRefusesUnmeasuredPair() {
        // The invariant is "modeled cost ≥ real cost". Any low fallback understates it; any high
        // fallback (gold's 0.265) overstates FX ~3000×. So an unmeasured pair must throw, not guess.
        assertThrows(IllegalArgumentException.class, () -> RealCostModel.halfSpread("EUR_JPY"));
        assertThrows(IllegalArgumentException.class, () -> RealCostModel.costFor("EUR_JPY"));
    }

    @Test
    void usdPerQuoteUnitConvertsQuoteCurrency() {
        assertEquals(1.0, RealCostModel.usdPerQuoteUnit("EUR_USD"), 1e-9);            // USD quote
        assertEquals(1.0 / 1.422295, RealCostModel.usdPerQuoteUnit("USD_CAD"), 1e-9); // CAD → ÷USD_CAD
        assertEquals(1.0 / 0.83099, RealCostModel.usdPerQuoteUnit("USD_CHF"), 1e-9);  // CHF → ÷USD_CHF
        assertEquals(1.31978, RealCostModel.usdPerQuoteUnit("EUR_GBP"), 1e-9);        // GBP → ×GBP_USD
        assertEquals(1.0 / 157.925, RealCostModel.usdPerQuoteUnit("USD_JPY"), 1e-9);  // JPY → ÷USD_JPY
    }

    @Test
    void costForHasZeroCommissionAndMeasuredHalfSpread() {
        BacktestExecutionCost c = RealCostModel.costFor("EUR_USD", 1.12456);
        assertEquals(0.0, c.commissionPerTrade(), 1e-9);
        assertEquals(0.00008, c.slippageFixed(), 1e-12);   // half-spread per leg
        assertTrue(c.stopSlippagePct() > 0);               // SL exits pay the half-spread too
    }
}
