package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the corrected swap model (FEE-AUDIT.md §4): rates are broker financing
 * (annual fraction → pips/day), JPY-quoted pip values are converted JPY→USD, and
 * metals use a 0.01 pip size. The previous table (3 wrong signs, gold ~32× too
 * small) is what these assertions replaced.
 */
class SwapCalculatorTest {

    private static final Instant OPEN = Instant.parse("2024-01-01T12:00:00Z");
    private static final Instant CLOSE = Instant.parse("2024-01-02T12:00:00Z"); // 1 rollover day

    @Test
    void gbpJpyLongSwapIsCreditAndConvertsToUsd() {
        // GBP_JPY long = +0.89 pips/day (GBP >> JPY carry; the old table said -4.5).
        // 1000 units × 0.01 pip = 10 JPY per pip → /150 = $0.0667 per pip
        // +0.89 × 0.0667 = +$0.059/day
        double swap = SwapCalculator.calculateSwap("GBP_JPY", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(0.059, swap, 0.01);
    }

    @Test
    void nonJpyPairUsesCorrectedRate() {
        // EUR_USD long = -0.76 pips/day (EUR < USD). 1000 units × 0.0001 = $0.10 per pip
        // -0.76 × 0.10 = -$0.076/day
        double swap = SwapCalculator.calculateSwap("EUR_USD", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(-0.076, swap, 0.005);
    }

    @Test
    void usdJpyConvertsPipValueToUsd() {
        // USD_JPY short = -1.65 pips/day. 1000 units × 0.01 = 10 JPY per pip → /150 = $0.0667
        double swap = SwapCalculator.calculateSwap("USD_JPY", Order.Side.SELL, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(-1.65 * 1000 * 0.01 / 150.0, swap, 0.005);
    }

    @Test
    void goldUsesCentPipSize() {
        // XAU_USD long = -65.2 pips/day with a 0.01 pip (the old table said -2.0).
        // 100 units (1 lot) × 0.01 = $1.00 per pip → -65.2 × $1 = -$65.2/day
        double swap = SwapCalculator.calculateSwap("XAU_USD", Order.Side.BUY, 100.0, OPEN, CLOSE, 150.0);
        assertEquals(-65.2, swap, 1.0);
    }

    @Test
    void noSwapWhenSameDay() {
        double swap = SwapCalculator.calculateSwap("GBP_JPY", Order.Side.BUY, 1000.0, OPEN, OPEN, 150.0);
        assertEquals(0.0, swap, 0.0001);
    }

    @Test
    void unknownPairNoSwap() {
        double swap = SwapCalculator.calculateSwap("XXX_YYY", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(0.0, swap, 0.0001);
    }

    @Test
    void swapTableLookupIgnoresUnderscores() {
        // The table key is now "USD_CAD" (underscore-normalized); an underscore-less
        // lookup must still resolve via samePair().
        assertTrue(SwapCalculator.hasRates("USD_CAD"));
        assertTrue(SwapCalculator.hasRates("USDCAD"));
        // USD_CAD long = +0.26 pips/day. 1000 units × 0.0001 = $0.10 per pip
        double swap = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(0.26 * 1000 * 0.0001, swap, 0.001);
    }

    @Test
    void yearlyOverrideAppliesTheRateOfTheRolloverYear() {
        java.util.Map<Integer, double[]> byYear = new java.util.HashMap<>();
        byYear.put(2024, new double[]{1.0, -1.0});
        try {
            SwapCalculator.setYearlyRateOverride("USD_CAD", byYear);
            // 1 rollover day in 2024 × +1.0 pip × $0.10/pip = +$0.10
            double swap = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
            assertEquals(0.10, swap, 0.001);
            // Année absente de la table et sans repli (-1) → aucun swap appliqué
            double outside = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0,
                Instant.parse("2030-01-01T12:00:00Z"), Instant.parse("2030-01-02T12:00:00Z"), 150.0);
            assertEquals(0.0, outside, 0.0001);
        } finally {
            SwapCalculator.clearRateOverride();
        }
    }
}
