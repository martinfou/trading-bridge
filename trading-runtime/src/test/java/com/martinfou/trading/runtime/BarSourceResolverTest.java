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

    @Test
    void stitchDukascopyAndRecentBars_alignsFuturesBasisAndQuantizesTicks() {
        java.time.Instant t1 = java.time.Instant.parse("2026-01-15T14:00:00Z");
        java.time.Instant t2 = java.time.Instant.parse("2026-01-15T14:30:00Z");
        java.time.Instant t3 = java.time.Instant.parse("2026-01-15T15:00:00Z");

        // Spot gold (XAUUSD) at ~2650.0, while MGC futures at t2 has +15.20 carry basis (open 2665.20 vs spot 2650.00)
        List<Bar> dukascopy = List.of(
            new Bar("MGC", t1, 2640.0, 2645.0, 2638.0, 2642.0, 1000L),
            new Bar("MGC", t2, 2650.0, 2655.0, 2648.0, 2652.0, 1100L)
        );
        List<Bar> recentIbkr = List.of(
            new Bar("MGC", t2, 2665.2, 2670.5, 2663.1, 2668.4, 500L),
            new Bar("MGC", t3, 2668.4, 2672.0, 2666.0, 2671.0, 600L)
        );

        List<Bar> stitched = HistoricalDataService.stitchDukascopyAndRecentBars(dukascopy, recentIbkr, "MGC");
        assertEquals(3, stitched.size());
        // t1 bar shifted by +15.20 basis -> open 2655.20, high 2660.20, low 2653.20, close 2657.20
        assertEquals(t1, stitched.get(0).timestamp());
        assertEquals(2655.2, stitched.get(0).open(), 1e-6);
        assertEquals(2660.2, stitched.get(0).high(), 1e-6);
        assertEquals(2653.2, stitched.get(0).low(), 1e-6);
        assertEquals(2657.2, stitched.get(0).close(), 1e-6);
        // t2 and t3 come directly from authoritative recentIbkr
        assertEquals(t2, stitched.get(1).timestamp());
        assertEquals(2665.2, stitched.get(1).open(), 1e-6);
        assertEquals(500L, stitched.get(1).volume());
        assertEquals(t3, stitched.get(2).timestamp());
    }
}
