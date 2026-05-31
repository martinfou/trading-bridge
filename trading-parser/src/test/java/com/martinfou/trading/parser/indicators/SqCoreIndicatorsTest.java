package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqCoreIndicatorsTest {

    @Test
    void sma_shift1_usesPriorBarWindow() {
        List<Bar> bars = bars(1, 2, 3, 4, 5);
        var params = new SqIndicatorParams(3, 1, SqAppliedPrice.CLOSE);
        assertEquals(3.0, SqCoreIndicators.sma(bars, params), 1e-9);
    }

    @Test
    void sma_openPrice_usesOpenField() {
        List<Bar> bars = List.of(
            bar(1.0, 2.0), bar(2.0, 3.0), bar(3.0, 4.0));
        var params = new SqIndicatorParams(2, 0, SqAppliedPrice.OPEN);
        assertEquals(2.5, SqCoreIndicators.sma(bars, params), 1e-9);
    }

    @Test
    void ema_matchesCoreOnCloseAtLatest() {
        List<Bar> bars = bars(1, 2, 3, 4, 5);
        var params = new SqIndicatorParams(3, 0, SqAppliedPrice.CLOSE);
        assertEquals(4.0, SqCoreIndicators.ema(bars, params), 1e-9);
    }

    @Test
    void rsi_delegatesToCore() {
        List<Bar> bars = bars(1.00, 1.01, 1.02, 1.03, 1.04);
        var params = new SqIndicatorParams(3, 0, SqAppliedPrice.CLOSE);
        assertEquals(100.0, SqCoreIndicators.rsi(bars, params), 1e-9);
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

    private static Bar bar(double open, double close) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        return new Bar("EUR_USD", t, open, close, open, close, 0);
    }
}
