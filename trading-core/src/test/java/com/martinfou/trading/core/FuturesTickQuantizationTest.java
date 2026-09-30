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

    @Test
    void testFuturesValuationModelQuantizesEntryAndExitSymmetrically() {
        AssetValuationModel model = AssetValuationRegistry.resolve("MES");

        // Off-tick entry 5000.10 snaps to 5000.00; on-tick exit 5002.25.
        // Symmetric quantization: (5002.25 - 5000.00) * $5 = $11.25 (NOT (5002.25 - 5000.10) * $5 = $10.75)
        double pnl = model.calculatePnL(Order.Side.BUY, 5000.10, 5002.25, 1.0, 150.0);
        assertEquals(11.25, pnl, 1e-6);

        // Off-tick entry on a SELL side must also quantize entry symmetrically.
        double shortPnl = model.calculatePnL(Order.Side.SELL, 5000.10, 4997.90, 1.0, 150.0);
        // entry 5000.10 -> 5000.00, exit 4997.90 -> 4998.00 ; (5000.00 - 4998.00) * 5 = $10.00
        assertEquals(10.00, shortPnl, 1e-6);
    }

    @Test
    void testMicroMultiplierIsNotOffByFactorTen() {
        // Regression lock-in: micro futures must use MICRO multipliers, never the full-size contract's.
        // ES=F (full E-mini) and MES=F (micro) quote the SAME index price; the $50 vs $5 difference
        // lives entirely in the multiplier. A 1-point move on MES must be worth $5, not $50.
        FuturesContract mes = FuturesRegistry.find("MES").orElseThrow();
        FuturesContract mnq = FuturesRegistry.find("MNQ").orElseThrow();
        FuturesContract m2k = FuturesRegistry.find("M2K").orElseThrow();

        assertEquals(5.0, mes.multiplier(), 1e-9);
        assertEquals(2.0, mnq.multiplier(), 1e-9);
        assertEquals(5.0, m2k.multiplier(), 1e-9);

        AssetValuationModel mesModel = AssetValuationRegistry.resolve("MES");
        assertEquals(5.0, mesModel.calculatePnL(Order.Side.BUY, 5000.0, 5001.0, 1.0, 150.0), 1e-6);

        // Notional of 1 MES contract at 5000 = $25,000 (micro), not $250,000 (full E-mini).
        assertEquals(25_000.0, mesModel.notionalValue(5000.0, 1.0), 1e-6);
    }
}

