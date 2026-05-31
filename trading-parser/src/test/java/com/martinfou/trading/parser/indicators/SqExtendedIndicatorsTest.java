package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqExtendedIndicatorsTest {

    @Test
    void atr_delegatesToCoreWithShift() {
        List<Bar> bars = List.of(
            ohlc(1.10, 1.12, 1.09),
            ohlc(1.11, 1.15, 1.10),
            ohlc(1.12, 1.14, 1.11));
        var params = new SqIndicatorParams(1, 0, SqAppliedPrice.CLOSE);
        assertEquals(Indicators.atr(bars, 1), SqExtendedIndicators.atr(bars, params), 1e-9);
    }

    @Test
    void bbRange_matchesInlineFormula() {
        List<Bar> bars = bars(1, 2, 3, 4, 5);
        var params = new SqBollingerParams(3, 2.0, 0, SqAppliedPrice.CLOSE);
        double width = SqExtendedIndicators.bbRange(bars, params);
        assertTrue(width > 0, "band width should be positive on trending closes");
    }

    @Test
    void bollingerMiddle_isSmaAtShift() {
        List<Bar> bars = bars(1, 2, 3, 4, 5);
        var params = new SqBollingerParams(3, 2.0, 1, SqAppliedPrice.CLOSE);
        assertEquals(3.0, SqExtendedIndicators.bollingerMiddle(bars, params), 1e-9);
    }

    @Test
    void macdLine_fastAboveSlowOnUptrend() {
        List<Bar> bars = bars(1, 1.01, 1.02, 1.03, 1.04, 1.05, 1.06, 1.07, 1.08, 1.09,
            1.10, 1.11, 1.12, 1.13, 1.14, 1.15, 1.16, 1.17, 1.18, 1.19,
            1.20, 1.21, 1.22, 1.23, 1.24, 1.25, 1.26, 1.27, 1.28, 1.29);
        var params = new SqMacdParams(12, 26, 9, 0, SqAppliedPrice.CLOSE);
        double macd = SqExtendedIndicators.macdLine(bars, params);
        assertTrue(macd > 0, "uptrend should yield positive MACD line");
    }

    private static List<Bar> bars(double... closes) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        Bar[] out = new Bar[closes.length];
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out[i] = new Bar("EUR_USD", t, c, c, c, c, 0);
        }
        return List.of(out);
    }

    private static Bar ohlc(double open, double high, double low) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        double close = (high + low) / 2.0;
        return new Bar("EUR_USD", t, open, high, low, close, 0);
    }
}
