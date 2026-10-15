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
    void goldUsesCentPipSizeAtPeriodAverageMid() {
        // XAU_USD long = -25.9 pips/day at the 2010-2025 period-average mid (1664), NOT today's
        // 4182 (which would be -65.2 and overstate 15 years of carry ~2.5×).
        // 100 units (1 lot) × 0.01 = $1.00 per pip → -25.9 × $1 = -$25.9/day
        double swap = SwapCalculator.calculateSwap("XAU_USD", Order.Side.BUY, 100.0, OPEN, CLOSE, 150.0);
        assertEquals(-25.9, swap, 1.0);
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
        // USD_CAD long = +0.26 pips/day. 1000 units × 0.0001 = 0.10 CAD per pip, ÷1.422295 → USD.
        double swap = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(0.26 * 1000 * 0.0001 / 1.422295, swap, 0.001);
    }

    @Test
    void nonUsdQuotePipValueConvertedToUsd() {
        // USD_CAD quote = CAD: pip value 0.10 CAD → ÷1.422295 = $0.0703 (was treated as $0.10).
        double cadSwap = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(0.26 * 1000 * 0.0001 / 1.422295, cadSwap, 0.001);
        // EUR_GBP quote = GBP: pip value 0.10 GBP → ×1.31978 = $0.132 (was treated as $0.10).
        // EUR_GBP long = -0.53 pips/day.
        double gbpSwap = SwapCalculator.calculateSwap("EUR_GBP", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(-0.53 * 1000 * 0.0001 * 1.31978, gbpSwap, 0.002);
        // USD_CHF quote = CHF: pip value 0.10 CHF → ÷0.83099 = $0.120 (was treated as $0.10).
        // USD_CHF long = +0.72 pips/day.
        double chfSwap = SwapCalculator.calculateSwap("USD_CHF", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(0.72 * 1000 * 0.0001 / 0.83099, chfSwap, 0.002);
    }

    @Test
    void yearlyOverrideAppliesTheRateOfTheRolloverYear() {
        java.util.Map<Integer, double[]> byYear = new java.util.HashMap<>();
        byYear.put(2024, new double[]{1.0, -1.0});
        try {
            SwapCalculator.setYearlyRateOverride("USD_CAD", byYear);
            // 1 rollover day in 2024 × +1.0 pip × (0.10 CAD/pip ÷ 1.422295 → USD) = +$0.0703
            double swap = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
            assertEquals(1.0 * 1000 * 0.0001 / 1.422295, swap, 0.001);
            // Année absente de la table et sans repli (-1) → aucun swap appliqué
            double outside = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0,
                Instant.parse("2030-01-01T12:00:00Z"), Instant.parse("2030-01-02T12:00:00Z"), 150.0);
            assertEquals(0.0, outside, 0.0001);
        } finally {
            SwapCalculator.clearRateOverride();
        }
    }

    @Test
    void yearlySwapModeDoesNotChargeWeekends() {
        // Le bug vivait dans DEUX chemins : countRollovers et yearlySwap. Sans ce test, un revert du
        // seul yearlySwap repasserait a 9 nuits par semaine avec une suite verte.
        java.util.Map<Integer, double[]> byYear = new java.util.HashMap<>();
        byYear.put(2024, new double[]{1.0, -1.0});
        try {
            SwapCalculator.setYearlyRateOverride("USD_CAD", byYear);
            // Lundi 00:00 UTC -> lundi suivant 00:00 UTC = 7 nuits (dont le triple du mercredi), pas 9.
            double week = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0,
                Instant.parse("2024-05-06T00:00:00Z"), Instant.parse("2024-05-13T00:00:00Z"), 150.0);
            assertEquals(7.0 * 1000 * 0.0001 / 1.422295, week, 0.001);
            // Deux semaines = 14 nuits, pas 18.
            double fortnight = SwapCalculator.calculateSwap("USD_CAD", Order.Side.BUY, 1000.0,
                Instant.parse("2024-05-06T00:00:00Z"), Instant.parse("2024-05-20T00:00:00Z"), 150.0);
            assertEquals(14.0 * 1000 * 0.0001 / 1.422295, fortnight, 0.001);
        } finally {
            SwapCalculator.clearRateOverride();
        }
    }

    @Test
    void weekendRolloverIsNotCounted() {
        // Vendredi 16:00 ET -> lundi 04:00 ET : un seul rollover reel, celui du vendredi soir. Le
        // week-end n'est pas un evenement de financement : il est porte par le triple du MERCREDI
        // (spot T+2 : la value date du mercredi est reportee du vendredi au lundi, donc 3 jours).
        // L'ancien compteur en facturait 3 (ven + sam + dim).
        assertEquals(1, SwapCalculator.countRollovers(
            Instant.parse("2024-05-03T20:00:00Z"), Instant.parse("2024-05-06T08:00:00Z")));
    }

    @Test
    void fullWeekIsSevenDaysNotNine() {
        // 4 charges ordinaires + 1 triple (mercredi) = 7 nuits, pas 9.
        assertEquals(7, SwapCalculator.countRollovers(
            Instant.parse("2024-05-06T00:00:00Z"), Instant.parse("2024-05-13T00:00:00Z")));
    }

    @Test
    void weekdayRolloversAreUnchanged() {
        // Non-regression : lundi -> mardi = 1, mercredi -> jeudi = 3 (triple).
        assertEquals(1, SwapCalculator.countRollovers(
            Instant.parse("2024-05-06T12:00:00Z"), Instant.parse("2024-05-07T12:00:00Z")));
        assertEquals(3, SwapCalculator.countRollovers(
            Instant.parse("2024-05-08T12:00:00Z"), Instant.parse("2024-05-09T12:00:00Z")));
    }
}
