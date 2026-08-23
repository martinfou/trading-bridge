package com.martinfou.trading.strategies;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StrategyTaxonomyTest {

    @Test
    @DisplayName("Every registered strategy must have complete taxonomy metadata")
    void allStrategiesHaveCompleteTaxonomy() {
        List<StrategyCatalog.Entry> entries = StrategyCatalog.entries();
        assertFalse(entries.isEmpty(), "Catalog must not be empty");

        for (StrategyCatalog.Entry entry : entries) {
            assertNotNull(entry.id(), "id must not be null");
            assertFalse(entry.id().isBlank(), "id must not be blank");
            assertNotNull(entry.family(), "family must not be null");
            assertNotNull(entry.defaultSymbol(), "defaultSymbol must not be null");
            assertNotNull(entry.type(), "type must not be null");
            assertNotNull(entry.indicators(), "indicators must not be null");
            assertNotNull(entry.description(), "description must not be null");

            // Taxonomy fields
            assertNotNull(entry.assetClasses(), "assetClasses must not be null for " + entry.id());
            assertFalse(entry.assetClasses().isEmpty(), "assetClasses must not be empty for " + entry.id());
            assertNotNull(entry.tradingStyle(), "tradingStyle must not be null for " + entry.id());
            assertFalse(entry.tradingStyle().isBlank(), "tradingStyle must not be blank for " + entry.id());
            assertNotNull(entry.timeframeSuitability(), "timeframeSuitability must not be null for " + entry.id());
            assertFalse(entry.timeframeSuitability().isEmpty(), "timeframeSuitability must not be empty for " + entry.id());
            assertNotNull(entry.recommendedSymbols(), "recommendedSymbols must not be null for " + entry.id());
            assertFalse(entry.recommendedSymbols().isEmpty(), "recommendedSymbols must not be empty for " + entry.id());
            assertNotNull(entry.complexity(), "complexity must not be null for " + entry.id());
            assertFalse(entry.complexity().isBlank(), "complexity must not be blank for " + entry.id());
        }
    }

    @Test
    @DisplayName("Futures strategies must contain FUTURES in assetClasses")
    void futuresStrategiesContainFuturesAssetClass() {
        StrategyCatalog.Entry totm = StrategyCatalog.entries().stream()
            .filter(e -> e.id().equals("FuturesTurnOfMonth"))
            .findFirst()
            .orElseThrow();
        assertTrue(totm.assetClasses().contains("FUTURES"), "FuturesTurnOfMonth must contain FUTURES");
        assertEquals("SEASONALITY", totm.tradingStyle());
        assertTrue(totm.recommendedSymbols().contains("MES"));
        assertTrue(totm.recommendedSymbols().contains("MNQ"));

        StrategyCatalog.Entry orb = StrategyCatalog.entries().stream()
            .filter(e -> e.id().equals("FuturesOpeningRangeBreakout"))
            .findFirst()
            .orElseThrow();
        assertTrue(orb.assetClasses().contains("FUTURES"), "FuturesOpeningRangeBreakout must contain FUTURES");
        assertEquals("BREAKOUT", orb.tradingStyle());
    }

    @Test
    @DisplayName("Multi-asset trend models support FUTURES, FOREX, and EQUITY")
    void multiAssetModelsSupportMultipleClasses() {
        StrategyCatalog.Entry breakout = StrategyCatalog.entries().stream()
            .filter(e -> e.id().equals("LtRangeBreakout"))
            .findFirst()
            .orElseThrow();
        assertTrue(breakout.assetClasses().contains("FUTURES"));
        assertTrue(breakout.assetClasses().contains("FOREX"));
        assertTrue(breakout.assetClasses().contains("EQUITY"));
    }
}
