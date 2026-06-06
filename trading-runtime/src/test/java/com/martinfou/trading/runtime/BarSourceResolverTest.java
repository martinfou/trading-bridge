package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BarSourceResolverTest {

    @Test
    void sampleBarsProducesRequestedCount() throws Exception {
        List<Bar> bars = BarSourceResolver.load(
            new BarSourceResolver.BarsSource("sample", 42, (Integer) null), "EUR_USD");
        assertEquals(42, bars.size());
        assertEquals("EUR_USD", bars.getFirst().symbol());
    }

    @Test
    void fileSourceLoadsCsv(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("EUR_USD_H1.csv");
        Files.writeString(csv, """
            timestamp,open,high,low,close,volume
            1704067200000,1.08,1.09,1.07,1.085,1000
            1704070800000,1.085,1.09,1.08,1.086,1100
            """);

        List<Bar> bars = BarSourceResolver.load(
            new BarSourceResolver.BarsSource("file", null, null, csv.toString()),
            "EUR_USD");
        assertEquals(2, bars.size());
    }

    @Test
    void fileSourceRequiresPath() {
        assertThrows(IllegalArgumentException.class, () -> BarSourceResolver.load(
            new BarSourceResolver.BarsSource("file", null, null, null),
            "EUR_USD"));
    }

    @Test
    void yearSourceRequiresYearSpec() {
        assertThrows(IllegalArgumentException.class, () -> BarSourceResolver.load(
            new BarSourceResolver.BarsSource("year", null, (Integer) null),
            "EUR_USD"));
    }

    @Test
    void yearSourceLoadsWhenHistoricalDataPresent() throws Exception {
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot == null) {
            return;
        }
        Path barsFile = repoRoot.resolve("data/historical/bars/EUR_USD_H1_2012.bars");
        if (!Files.exists(barsFile)) {
            return;
        }
        List<Bar> bars = BarSourceResolver.load(
            new BarSourceResolver.BarsSource("year", null, 2012),
            "EUR_USD");
        assertFalse(bars.isEmpty());
    }
}
