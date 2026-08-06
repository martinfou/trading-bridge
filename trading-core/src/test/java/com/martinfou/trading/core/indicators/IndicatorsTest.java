package com.martinfou.trading.core.indicators;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class IndicatorsTest {

    @Test
    void pipSize_computesCorrectly() {
        assertEquals(0.01, Indicators.pipSize("USD_JPY"));
        assertEquals(0.01, Indicators.pipSize("EUR_JPY"));
        assertEquals(0.0001, Indicators.pipSize("EUR_USD"));
        assertEquals(0.0001, Indicators.pipSize("GBP_USD"));
    }

    @Test
    void sma_computesSimpleAverage() {
        List<Bar> bars = List.of(bar(1.0), bar(2.0), bar(3.0), bar(4.0), bar(5.0));
        assertEquals(2.0, Indicators.sma(bars, 3, 2), 1e-9);
        assertEquals(3.0, Indicators.sma(bars, 3, 3), 1e-9);
        assertEquals(4.0, Indicators.sma(bars, 3, 4), 1e-9);
    }

    @Test
    void sma_returnsNaNWhenNotEnoughBars() {
        List<Bar> bars = List.of(bar(1.0), bar(2.0));
        assertTrue(Double.isNaN(Indicators.sma(bars, 3, 1)));
    }

    @Test
    void smaLatest_computesSimpleAverage() {
        List<Bar> bars = List.of(bar(1.0), bar(2.0), bar(3.0), bar(4.0), bar(5.0));
        assertEquals(4.0, Indicators.smaLatest(bars, 3), 1e-9);
    }

    @Test
    void smaLatest_returnsNaNForEmptyBars() {
        assertTrue(Double.isNaN(Indicators.smaLatest(Collections.emptyList(), 3)));
    }

    @Test
    void emaLatest_weightsRecentCloses() {
        List<Bar> bars = List.of(bar(1.0), bar(2.0), bar(3.0), bar(4.0), bar(5.0));
        double ema = Indicators.emaLatest(bars, 3);
        assertEquals(4.0, ema, 1e-9); // initial SMA = 2.0, EMA4 = 4*0.5 + 2*0.5 = 3.0, EMA5 = 5*0.5 + 3*0.5 = 4.0
    }

    @Test
    void emaLatest_returnsNaNWhenNotEnoughBars() {
        List<Bar> bars = List.of(bar(1.0), bar(2.0));
        assertTrue(Double.isNaN(Indicators.emaLatest(bars, 3)));
    }

    @Test
    void atr_usesTrueRange() {
        List<Bar> bars = List.of(
            bar(1.10, 1.12, 1.09), // prev close 1.105
            bar(1.11, 1.15, 1.10)); // high:1.15 low:1.10 -> TR = max(0.05, 1.15-1.105, 1.105-1.10) = 0.05
        double atr = Indicators.atr(bars, 1);
        assertEquals(0.05, atr, 1e-9);
    }

    @Test
    void atr_returnsNaNWhenNotEnoughBars() {
        List<Bar> bars = List.of(bar(1.10, 1.12, 1.09));
        assertTrue(Double.isNaN(Indicators.atr(bars, 1)));
    }

    @Test
    void rsi_allGains_returns100() {
        List<Bar> bars = List.of(bar(1.00), bar(1.01), bar(1.02), bar(1.03), bar(1.04));
        assertEquals(100.0, Indicators.rsi(bars, 3), 1e-9);
    }

    @Test
    void rsi_mixedGainsAndLosses() {
        List<Bar> bars = List.of(
            bar(1.00), bar(1.01), bar(1.00), bar(1.02), bar(1.01));
        // diffs: +0.01, -0.01, +0.02, -0.01
        // rsi(3) uses last 3 diffs: -0.01, +0.02, -0.01
        // gain = 0.02, loss = 0.02
        // rs = 1.0
        // rsi = 100 - 100/2 = 50.0
        assertEquals(50.0, Indicators.rsi(bars, 3), 1e-9);
    }

    @Test
    void rsi_returnsNaNWhenNotEnoughBars() {
        List<Bar> bars = List.of(bar(1.0));
        assertTrue(Double.isNaN(Indicators.rsi(bars, 1)));
    }

    @Test
    void rsi2_allGains_returns100() {
        List<Bar> bars = List.of(bar(1.00), bar(1.01), bar(1.02), bar(1.03), bar(1.04));
        assertEquals(100.0, Indicators.rsi2(bars), 1e-9);
    }

    @Test
    void rsi2_mixedGainsAndLosses() {
        List<Bar> bars = List.of(bar(1.00), bar(1.02), bar(1.01));
        // diffs: +0.02, -0.01
        // rsi2 uses last 2 diffs: +0.02, -0.01
        // gain = 0.02, loss = 0.01 -> RS = 2.0 -> RSI = 100 - (100/3) = 66.666...
        assertEquals(66.666666667, Indicators.rsi2(bars), 1e-9);
    }

    @Test
    void rsi2_returnsNaNWhenNotEnoughBars() {
        List<Bar> bars = List.of(bar(1.0), bar(2.0));
        assertTrue(Double.isNaN(Indicators.rsi2(bars)));
    }

    @Test
    void isBullishEngulfing() {
        Bar prev = bar(1.10, 1.10, 1.08, 1.08); // Bearish, open 1.10, close 1.08
        Bar cur = bar(1.07, 1.11, 1.07, 1.11); // Bullish, open 1.07, close 1.11
        assertTrue(Indicators.isBullishEngulfing(prev, cur));

        Bar notEngulfing = bar(1.09, 1.11, 1.07, 1.11); // Open 1.09 (not lower than prev close 1.08)
        assertFalse(Indicators.isBullishEngulfing(prev, notEngulfing));
    }

    @Test
    void isBearishEngulfing() {
        Bar prev = bar(1.08, 1.10, 1.08, 1.10); // Bullish, open 1.08, close 1.10
        Bar cur = bar(1.11, 1.11, 1.07, 1.07); // Bearish, open 1.11, close 1.07
        assertTrue(Indicators.isBearishEngulfing(prev, cur));

        Bar notEngulfing = bar(1.09, 1.11, 1.07, 1.07); // Open 1.09 (not higher than prev close 1.10)
        assertFalse(Indicators.isBearishEngulfing(prev, notEngulfing));
    }

    @Test
    void bollingerWidth() {
        List<Bar> bars = List.of(bar(10.0), bar(12.0), bar(14.0), bar(16.0), bar(18.0));
        // For period 3, last 3 closes: 14.0, 16.0, 18.0. Mid = 16.0
        // Variance = ((14-16)^2 + (16-16)^2 + (18-16)^2) / 3 = (4 + 0 + 4) / 3 = 8/3 = 2.666...
        // Std = sqrt(8/3) = 1.63299
        double[] bw = Indicators.bollingerWidth(bars, 3, 2.0);
        assertEquals(32.0, bw[0] + bw[1], 1e-5); // Mid * 2
        assertEquals(16.0 - 2 * Math.sqrt(8.0/3.0), bw[0], 1e-5); // Lower band
        assertEquals(16.0 + 2 * Math.sqrt(8.0/3.0), bw[1], 1e-5); // Upper band
        assertEquals(4 * Math.sqrt(8.0/3.0), bw[2], 1e-5); // Band width
    }

    @Test
    void bollingerWidth_returnsNaNWhenNotEnoughBars() {
        List<Bar> bars = List.of(bar(10.0), bar(12.0));
        double[] bw = Indicators.bollingerWidth(bars, 3, 2.0);
        assertTrue(Double.isNaN(bw[0]));
        assertTrue(Double.isNaN(bw[1]));
        assertTrue(Double.isNaN(bw[2]));
    }

    @Test
    void riskRewardTp_longSide() {
        double tp = Indicators.riskRewardTp(1.10, 1.08, Indicators.TradeSide.LONG, 2.5);
        assertEquals(1.15, tp, 1e-9);
    }

    @Test
    void riskRewardTp_shortSide() {
        double tp = Indicators.riskRewardTp(1.10, 1.12, Indicators.TradeSide.SHORT, 2.0);
        assertEquals(1.06, tp, 1e-9);
    }

    @Test
    void calcRiskPosition() {
        long posUsd = Indicators.calcRiskPosition(10000, 0.01, 0.0050, 2.0, "EUR_USD");
        // slPips = (0.0050 * 2.0) / 0.0001 = 100 pips
        // pipValue = 0.0001
        // riskAmount = 10000 * 0.01 = 100
        // units = 100 / (100 * 0.0001) = 10000
        assertEquals(10000, posUsd);

        long posJpy = Indicators.calcRiskPosition(10000, 0.01, 0.50, 2.0, "USD_JPY");
        // slPips = (0.50 * 2.0) / 0.01 = 100 pips
        // pipValue = 0.0000625
        // units = 100 / (100 * 0.0000625) = 100 / 0.00625 = 16000
        assertEquals(16000, posJpy);
    }

    @Test
    void calcRiskPosition_invalidValues() {
        assertEquals(1000, Indicators.calcRiskPosition(10000, 0.01, 0, 2.0, "EUR_USD"));
        assertEquals(1000, Indicators.calcRiskPosition(0, 0.01, 0.0050, 2.0, "EUR_USD"));
        assertEquals(1000, Indicators.calcRiskPosition(10000, 0.01, -0.0050, 2.0, "EUR_USD"));
    }

    private static Bar bar(double close) {
        return bar(close, close, close, close);
    }

    private static Bar bar(double open, double high, double low) {
        double close = (high + low) / 2.0;
        return bar(open, high, low, close);
    }

    private static Bar bar(double open, double high, double low, double close) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        return new Bar("EUR_USD", t, open, high, low, close, 0);
    }
}
