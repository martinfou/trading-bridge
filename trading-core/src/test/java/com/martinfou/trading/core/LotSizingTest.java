package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LotSizingTest {

    @Test
    void resolveCapitalUsesDefaultWhenNull() {
        assertEquals(1_000.0, LotSizing.resolveCapital(null), 1e-9);
    }

    @Test
    void resolveCapitalUsesExplicitValue() {
        assertEquals(2_000.0, LotSizing.resolveCapital(2_000.0), 1e-9);
    }

    @Test
    void resolveQuantityFromLotsConvertsForexSpotMetalsFuturesAndEquities() {
        assertEquals(10_000.0, LotSizing.resolveQuantityFromLots(0.10, "EUR_USD"), 1e-9);
        assertEquals(10_000.0, LotSizing.resolveQuantityFromLots(0.10, "GBP_JPY"), 1e-9);
        assertEquals(10.0, LotSizing.resolveQuantityFromLots(0.10, "XAU_USD"), 1e-9);
        assertEquals(1.0, LotSizing.resolveQuantityFromLots(1.0, "MGC"), 1e-9);
        assertEquals(1.0, LotSizing.resolveQuantityFromLots(1.0, "GC"), 1e-9);
        assertEquals(10.0, LotSizing.resolveQuantityFromLots(10.0, "AAPL"), 1e-9);
    }

    @Test
    void defaultsForSymbolMatchTieredAssetClasses() {
        LotSizing.InstrumentDefaults forex = LotSizing.defaultsForSymbol("EUR_USD");
        assertEquals(10_000.0, forex.capital(), 1e-9);
        assertEquals(0.10, forex.lotSize(), 1e-9);
        assertEquals(0.70, forex.commissionPerTrade(), 1e-9);
        assertEquals(0.00005, forex.slippagePct(), 1e-9);

        LotSizing.InstrumentDefaults micro = LotSizing.defaultsForSymbol("MGC");
        assertEquals(10_000.0, micro.capital(), 1e-9);
        assertEquals(1.0, micro.lotSize(), 1e-9);
        assertEquals(0.62, micro.commissionPerTrade(), 1e-9);
        assertEquals(0.00005, micro.slippagePct(), 1e-9);

        LotSizing.InstrumentDefaults full = LotSizing.defaultsForSymbol("GC");
        assertEquals(50_000.0, full.capital(), 1e-9);
        assertEquals(1.0, full.lotSize(), 1e-9);
        assertEquals(2.10, full.commissionPerTrade(), 1e-9);
        assertEquals(0.00005, full.slippagePct(), 1e-9);

        LotSizing.InstrumentDefaults equity = LotSizing.defaultsForSymbol("AAPL");
        assertEquals(10_000.0, equity.capital(), 1e-9);
        assertEquals(10.0, equity.lotSize(), 1e-9);
        assertEquals(1.00, equity.commissionPerTrade(), 1e-9);
        assertEquals(0.0002, equity.slippagePct(), 1e-9);
    }
}
