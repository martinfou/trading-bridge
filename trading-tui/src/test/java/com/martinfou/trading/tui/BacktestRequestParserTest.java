package com.martinfou.trading.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacktestRequestParserTest {

    @Test
    void defaultIsSample500() {
        var parsed = BacktestRequestParser.parse(List.of("backtest", "LondonOpenRangeBreakout"));
        assertEquals("EUR_USD", parsed.symbol());
        assertEquals("sample", parsed.barsSource().get("type"));
        assertEquals(500, parsed.barsSource().get("count"));
    }

    @Test
    void symbolYearResolvesHistoricalYear() {
        var parsed = BacktestRequestParser.parse(
            List.of("backtest", "LondonOpenRangeBreakout", "EUR_USD", "2012"));
        assertEquals("EUR_USD", parsed.symbol());
        assertEquals("year", parsed.barsSource().get("type"));
        assertEquals("2012", parsed.barsSource().get("year"));
    }

    @Test
    void symbolYearRange() {
        var parsed = BacktestRequestParser.parse(
            List.of("backtest", "LondonOpenRangeBreakout", "EUR_USD", "2006-2012"));
        assertEquals("year", parsed.barsSource().get("type"));
        assertEquals("2006-2012", parsed.barsSource().get("year"));
    }

    @Test
    void legacySymbolBarCount() {
        var parsed = BacktestRequestParser.parse(
            List.of("backtest", "LondonOpenRangeBreakout", "GBP_USD", "1000"));
        assertEquals("GBP_USD", parsed.symbol());
        assertEquals("sample", parsed.barsSource().get("type"));
        assertEquals(1000, parsed.barsSource().get("count"));
    }

    @Test
    void sampleFlag() {
        var parsed = BacktestRequestParser.parse(List.of("backtest", "SmaCrossover", "--sample", "200"));
        assertEquals("sample", parsed.barsSource().get("type"));
        assertEquals(200, parsed.barsSource().get("count"));
    }

    @Test
    void ciFlag() {
        var parsed = BacktestRequestParser.parse(List.of("backtest", "SmaCrossover", "--ci"));
        assertEquals("ci", parsed.barsSource().get("type"));
    }

    @Test
    void csvPath() {
        var parsed = BacktestRequestParser.parse(
            List.of("backtest", "SmaCrossover", "data/historical/bars/EUR_USD_H1_2012.bars"));
        assertEquals("file", parsed.barsSource().get("type"));
        assertEquals("data/historical/bars/EUR_USD_H1_2012.bars", parsed.barsSource().get("path"));
    }

    @Test
    void rejectsAmbiguousFourthArg() {
        assertThrows(IllegalArgumentException.class, () -> BacktestRequestParser.parse(
            List.of("backtest", "SmaCrossover", "EUR_USD", "not-a-year")));
    }

    @Test
    void capitalAndLotFlags() {
        var parsed = BacktestRequestParser.parse(List.of(
            "backtest",
            "LondonOpenRangeBreakout",
            "GBP_JPY",
            "2012",
            "--capital",
            "50000",
            "--lots",
            "0.05"));
        assertEquals("GBP_JPY", parsed.symbol());
        assertEquals("2012", parsed.barsSource().get("year"));
        assertEquals(50_000.0, parsed.capital());
        assertEquals(0.05, parsed.lotSize());
    }

    @Test
    void futuresSymbolAndCostTimeframeFlags() {
        var parsed = BacktestRequestParser.parse(List.of(
            "backtest",
            "LondonOpenRangeBreakout",
            "MES",
            "2022-2025",
            "--tf",
            "m15",
            "--comm",
            "0.62",
            "--slip",
            "0.00005"));
        assertEquals("MES", parsed.symbol());
        assertEquals("2022-2025", parsed.barsSource().get("year"));
        assertEquals("M15", parsed.timeframe());
        assertEquals(0.62, parsed.commissionPerTrade());
        assertEquals(0.00005, parsed.slippagePct());
    }

    @Test
    void smartPresetsResolvePerAssetClass() {
        var micro = TuiDefaults.resolvePreset("MES");
        assertEquals("FUTURES", micro.assetClass());
        assertEquals(10_000.0, micro.capital());
        assertEquals(1.0, micro.lotSize());
        assertEquals(0.62, micro.commissionPerTrade());

        var full = TuiDefaults.resolvePreset("GC");
        assertEquals("FUTURES", full.assetClass());
        assertEquals(50_000.0, full.capital());
        assertEquals(2.10, full.commissionPerTrade());

        var fx = TuiDefaults.resolvePreset("EUR_USD");
        assertEquals("FOREX", fx.assetClass());
        assertEquals(10_000.0, fx.capital());
        assertEquals(0.10, fx.lotSize());
        assertEquals(0.70, fx.commissionPerTrade());

        var eq = TuiDefaults.resolvePreset("AAPL");
        assertEquals("EQUITIES", eq.assetClass());
        assertEquals(10_000.0, eq.capital());
        assertEquals(10.0, eq.lotSize());
        assertEquals(1.00, eq.commissionPerTrade());
    }
}
