package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqIndicatorRegistryTest {

    @Test
    void supports_coreKeys() {
        assertTrue(SqIndicatorRegistry.supports("SMA"));
        assertTrue(SqIndicatorRegistry.supports("EMA"));
        assertTrue(SqIndicatorRegistry.supports("RSI"));
        assertTrue(SqIndicatorRegistry.supports("ATR"));
        assertTrue(SqIndicatorRegistry.supports("BBRange"));
        assertTrue(SqIndicatorRegistry.supports("BollingerBands"));
        assertTrue(SqIndicatorRegistry.supports("MACD"));
        assertFalse(SqIndicatorRegistry.supports("Vortex"));
    }

    @Test
    void evaluate_atrItem() {
        SqXmlItem item = new SqXmlItem(
            "ATR", "ATR", "ATR", "indicator", "number",
            List.of(
                new SqXmlParam("#Period#", "int", "1", false, null),
                new SqXmlParam("#Shift#", "int", "0", false, null)
            ),
            List.of()
        );
        List<Bar> bars = List.of(
            bar(1.10, 1.12, 1.09),
            bar(1.11, 1.15, 1.10));
        double atr = SqIndicatorRegistry.evaluate(item, bars).orElseThrow();
        assertEquals(0.05, atr, 1e-9);
    }

    @Test
    void evaluate_smaItem() {
        SqXmlItem item = new SqXmlItem(
            "SMA", "SMA", "SMA", "indicator", "number",
            List.of(
                new SqXmlParam("#Period#", "int", "3", false, null),
                new SqXmlParam("#Shift#", "int", "0", false, null)
            ),
            List.of()
        );
        List<Bar> bars = List.of(
            bar(1), bar(2), bar(3), bar(4), bar(5));
        double sma = SqIndicatorRegistry.evaluate(item, bars).orElseThrow();
        assertEquals(4.0, sma, 1e-9);
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
