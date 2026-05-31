package com.martinfou.trading.parser.sq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqImportedBlockInventoryTest {

    @Test
    void vortexFamily_mapsToInlineHelpers() {
        var mapping = SqImportedBlockInventory.bySqItemKey("Vortex").orElseThrow();
        assertEquals("E", mapping.catalogueFamily());
        assertEquals(SqImportedBlockInventory.SupportLevel.INLINE, mapping.support());
    }

    @Test
    void linRegFamily_mapsToCalcLinReg() {
        var mapping = SqImportedBlockInventory.bySqItemKey("LinearRegression").orElseThrow();
        assertEquals(SqImportedBlockInventory.SupportLevel.INLINE, mapping.support());
        assertTrue(mapping.bridgeTarget().contains("LinReg"));
    }

    @Test
    void atr_mapsToCoreIndicators() {
        var mapping = SqImportedBlockInventory.bySqItemKey("ATR").orElseThrow();
        assertEquals(SqImportedBlockInventory.SupportLevel.CORE, mapping.support());
    }

    @Test
    void gaps_includeIchimokuAndSuperTrend() {
        var gaps = SqImportedBlockInventory.gaps();
        assertTrue(gaps.stream().anyMatch(g -> "Ichimoku".equals(g.sqItemKey())));
        assertTrue(gaps.stream().anyMatch(g -> "SuperTrend".equals(g.sqItemKey())));
    }

    @Test
    void familyB_includesKeltner() {
        var blocks = SqImportedBlockInventory.byFamily("B");
        assertTrue(blocks.stream().anyMatch(b -> "KeltnerChannel".equals(b.sqItemKey())));
    }
}
