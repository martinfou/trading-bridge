package com.martinfou.trading.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalDataCatalogTest {

    @Test
    void listsSymbolsAndYearsFromBars(@TempDir Path root) throws Exception {
        Path bars = root.resolve("bars");
        Files.createDirectories(bars);
        Files.createFile(bars.resolve("EUR_USD_H1_2011.bars"));
        Files.createFile(bars.resolve("EUR_USD_H1_2012.bars"));
        Files.createFile(bars.resolve("GBP_USD_H1_2012.bars"));

        List<String> symbols = HistoricalDataCatalog.listSymbols(bars, root.resolve("csv"));
        assertTrue(symbols.contains("EUR_USD"));
        assertTrue(symbols.contains("GBP_USD"));

        var availability = HistoricalDataCatalog.availability("EUR_USD", bars, root.resolve("csv"));
        assertEquals(2011, availability.minYear());
        assertEquals(2012, availability.maxYear());
        assertEquals("2011-2012", availability.suggestedRange());
        assertEquals(List.of(), availability.gaps());
    }

    @Test
    void detectsGapsInYearRange(@TempDir Path root) throws Exception {
        Path bars = root.resolve("bars");
        Files.createDirectories(bars);
        Files.createFile(bars.resolve("EUR_USD_H1_2010.bars"));
        Files.createFile(bars.resolve("EUR_USD_H1_2012.bars"));

        var availability = HistoricalDataCatalog.availability("EUR_USD", bars, root.resolve("csv"));
        assertEquals(List.of(2011), availability.gaps());
    }

    @Test
    void countsYearsFromNonHourlyBarsToo(@TempDir Path root) throws Exception {
        Path bars = root.resolve("bars");
        Files.createDirectories(bars);
        Files.createFile(bars.resolve("MGC_M30_2025.bars"));
        Files.createFile(bars.resolve("MGC_M30_2026.bars"));

        assertTrue(HistoricalDataCatalog.listSymbols(bars, root.resolve("csv")).contains("MGC"));

        var anyTf = HistoricalDataCatalog.availability("MGC", bars, root.resolve("csv"));
        assertEquals(List.of(2025, 2026), anyTf.years(), "coverage means data exists at any timeframe");

        var m30 = HistoricalDataCatalog.availability("MGC", "M30", bars, root.resolve("csv"));
        assertEquals(List.of(2025, 2026), m30.years(), "M30 bars serve an M30 strategy without a download");

        var h4 = HistoricalDataCatalog.availability("MGC", "H4", bars, root.resolve("csv"));
        assertEquals(List.of(2025, 2026), h4.years(), "M30 bars aggregate into H4");
    }

    @Test
    void finerBarsDoNotClaimAvailabilityForAFinerStrategy(@TempDir Path root) throws Exception {
        Path bars = root.resolve("bars");
        Files.createDirectories(bars);
        Files.createFile(bars.resolve("MGC_M30_2026.bars"));

        var m1 = HistoricalDataCatalog.availability("MGC", "M1", bars, root.resolve("csv"));
        assertEquals(List.of(), m1.years(), "M30 cannot be split into M1, the download path must stay open");
    }

    @Test
    void ignoresRangeFilesAndBlankTimeframes(@TempDir Path root) throws Exception {
        Path bars = root.resolve("bars");
        Files.createDirectories(bars);
        Files.createFile(bars.resolve("MGC_M30_2003-2026.bars"));

        var rangeOnly = HistoricalDataCatalog.availability("MGC", "M30", bars, root.resolve("csv"));
        assertEquals(List.of(), rangeOnly.years(), "a start-end file names no single year");

        Files.createFile(bars.resolve("MGC_M30_2024.bars"));
        var blank = HistoricalDataCatalog.availability("MGC", "  ", bars, root.resolve("csv"));
        assertEquals(List.of(2024), blank.years(), "a blank timeframe counts any data instead of throwing");
    }

    @Test
    void hourlyCsvOnlyServesTimeframesItCanBeAggregatedInto(@TempDir Path root) throws Exception {
        Path csv = root.resolve("csv");
        Files.createDirectories(csv);
        Files.createFile(csv.resolve("eurusd-h1-2020.csv"));

        var h4 = HistoricalDataCatalog.availability("EUR_USD", "H4", root.resolve("bars"), csv);
        assertEquals(List.of(2020), h4.years(), "H1 candles aggregate into H4");

        var m30 = HistoricalDataCatalog.availability("EUR_USD", "M30", root.resolve("bars"), csv);
        assertEquals(List.of(), m30.years(), "H1 candles cannot be split into M30");
    }
}
