package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the JPY swap-rate fix: pip values for JPY-quoted pairs must be
 * converted from JPY to USD, otherwise swap costs are overstated ~150x and
 * (because totalSwap is signed negative) totalPnl gets inflated.
 */
class SwapCalculatorTest {

    private static final Instant OPEN = Instant.parse("2024-01-01T12:00:00Z");
    private static final Instant CLOSE = Instant.parse("2024-01-02T12:00:00Z"); // 1 rollover day

    @Test
    void gbpJpySwapConvertsPipValueToUsd() {
        // GBP_JPY long swap = -4.5 pips/day for a standard lot.
        // 1000 units × 0.01 pip = 10 JPY per pip → /150 = $0.0667 per pip
        // -4.5 × 0.0667 = -$0.30/day
        double swap = SwapCalculator.calculateSwap("GBP_JPY", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(-0.30, swap, 0.01);
    }

    @Test
    void nonJpyPairUnchanged() {
        // EUR_USD long swap = -3.5 pips/day. 1000 units × 0.0001 = $0.10 per pip
        // -3.5 × 0.10 = -$0.35/day
        double swap = SwapCalculator.calculateSwap("EUR_USD", Order.Side.BUY, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(-0.35, swap, 0.001);
    }

    @Test
    void usdJpyConvertsPipValueToUsd() {
        // USD_JPY short swap = -8.5 pips/day. 1000 units × 0.01 = 10 JPY per pip → /150 = $0.0667
        double swap = SwapCalculator.calculateSwap("USD_JPY", Order.Side.SELL, 1000.0, OPEN, CLOSE, 150.0);
        assertEquals(-8.5 * 1000 * 0.01 / 150.0, swap, 0.001);
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
}
