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
}
