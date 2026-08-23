package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FuturesTickQuantizationTest {

    @Test
    void testMesTickQuantization() {
        FuturesContract mes = FuturesRegistry.find("MES").orElseThrow();
        assertEquals(0.25, mes.minTick(), 1e-6);
        assertEquals(1.25, mes.tickValue(), 1e-6);

        // Valid ticks
        assertTrue(mes.isValidTick(5000.00));
        assertTrue(mes.isValidTick(5000.25));
        assertTrue(mes.isValidTick(5000.50));
        assertTrue(mes.isValidTick(5000.75));

        // Off-tick values
        assertFalse(mes.isValidTick(5000.10));
        assertFalse(mes.isValidTick(5000.3333));
        assertFalse(mes.isValidTick(5000.67));

        // Snapping off-tick prices to nearest 0.25
        assertEquals(5000.00, mes.quantizePrice(5000.10), 1e-6);
        assertEquals(5000.25, mes.quantizePrice(5000.15), 1e-6);
        assertEquals(5000.25, mes.quantizePrice(5000.3333), 1e-6);
        assertEquals(5000.50, mes.quantizePrice(5000.40), 1e-6);
        assertEquals(5000.75, mes.quantizePrice(5000.70), 1e-6);
        assertEquals(5001.00, mes.quantizePrice(5000.90), 1e-6);

        // Tick differences
        assertEquals(1, mes.tickDifference(5000.00, 5000.25));
        assertEquals(4, mes.tickDifference(5000.00, 5001.00));
        assertEquals(-2, mes.tickDifference(5000.50, 5000.00));
    }

    @Test
    void testM2kTickQuantization() {
        FuturesContract m2k = FuturesRegistry.find("M2K").orElseThrow();
        assertEquals(0.10, m2k.minTick(), 1e-6);
        assertEquals(0.50, m2k.tickValue(), 1e-6);

        assertTrue(m2k.isValidTick(2000.10));
        assertFalse(m2k.isValidTick(2000.15));

        assertEquals(2000.20, m2k.quantizePrice(2000.16), 1e-6);
        assertEquals(2000.10, m2k.quantizePrice(2000.14), 1e-6);
    }

    @Test
    void testFuturesRegistryQuantizeHelper() {
        assertEquals(5010.25, FuturesRegistry.quantizePrice("MES", 5010.30), 1e-6);
        assertEquals(5010.25, FuturesRegistry.quantizePrice("MES_202412", 5010.28), 1e-6);
        assertEquals(1.08542, FuturesRegistry.quantizePrice("EUR_USD", 1.08542), 1e-6); // Non-futures untouched
    }

    @Test
    void testFuturesValuationModelQuantizedPnL() {
        AssetValuationModel model = AssetValuationRegistry.resolve("MES");

        // Entry at 5000.00, Exit at 5002.25 (+2.25 pts = 9 ticks)
        // 9 ticks * $1.25 * 1 contract = $11.25
        double pnl = model.calculatePnL(Order.Side.BUY, 5000.00, 5002.25, 1.0, 150.0);
        assertEquals(11.25, pnl, 1e-6);

        // Exit at off-tick 5002.30 snaps to 5002.25 -> still $11.25
        double pnlOffTick = model.calculatePnL(Order.Side.BUY, 5000.00, 5002.30, 1.0, 150.0);
        assertEquals(11.25, pnlOffTick, 1e-6);
    }
}

