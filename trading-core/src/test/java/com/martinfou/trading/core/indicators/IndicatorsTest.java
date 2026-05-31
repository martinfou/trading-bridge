package com.martinfou.trading.core.indicators;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndicatorsTest {

    @Test
    void smaLatest_computesSimpleAverage() {
        List<Bar> bars = List.of(
            bar(1.0), bar(2.0), bar(3.0), bar(4.0), bar(5.0));
        assertEquals(4.0, Indicators.smaLatest(bars, 3), 1e-9);
    }

    @Test
    void emaLatest_weightsRecentCloses() {
        List<Bar> bars = List.of(bar(1.0), bar(2.0), bar(3.0), bar(4.0), bar(5.0));
        double ema = Indicators.emaLatest(bars, 3);
        assertEquals(4.0, ema, 1e-9);
    }

    @Test
    void atr_usesTrueRange() {
        List<Bar> bars = List.of(
            bar(1.10, 1.12, 1.09),
            bar(1.11, 1.15, 1.10));
        double atr = Indicators.atr(bars, 1);
        assertEquals(0.05, atr, 1e-9);
    }

    @Test
    void rsi_allGains_returns100() {
        List<Bar> bars = List.of(
            bar(1.00), bar(1.01), bar(1.02), bar(1.03), bar(1.04));
        assertEquals(100.0, Indicators.rsi(bars, 3), 1e-9);
    }

    @Test
    void riskRewardTp_longSide() {
        double tp = Indicators.riskRewardTp(1.10, 1.08, Indicators.TradeSide.LONG, 2.5);
        assertEquals(1.15, tp, 1e-9);
    }

    private static Bar bar(double close) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        return new Bar("EUR_USD", t, close, close, close, close, 0);
    }

    private static Bar bar(double open, double high, double low) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        double close = (high + low) / 2.0;
        return new Bar("EUR_USD", t, open, high, low, close, 0);
    }
}
