package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataLoaderTest {

    @Test
    void loadCSV_utcWallClock(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("bars.csv");
        Files.writeString(csv, """
            DateTime,Open,High,Low,Close,Volume
            2024-01-01T12:00:00,1.1,1.2,1.0,1.15,100
            """);

        List<Bar> bars = DataLoader.loadCSV(csv, "EURUSD");
        assertEquals(1, bars.size());
        assertEquals(Instant.parse("2024-01-01T12:00:00Z"), bars.get(0).timestamp());
    }

    @Test
    void loadCSV_withZSuffix(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("bars-z.csv");
        Files.writeString(csv, """
            DateTime,Open,High,Low,Close,Volume
            2024-01-01T12:00:00Z,1.1,1.2,1.0,1.15,100
            """);

        List<Bar> bars = DataLoader.loadCSV(csv, "EURUSD");
        assertEquals(1, bars.size());
        assertEquals(Instant.parse("2024-01-01T12:00:00Z"), bars.get(0).timestamp());
    }
}
