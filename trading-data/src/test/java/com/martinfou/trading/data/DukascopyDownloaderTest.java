package com.martinfou.trading.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DukascopyDownloaderTest {

    @Test
    void downloadRange_downloadsAndParsesData(@TempDir Path dir) throws Exception {
        DukascopyDownloader downloader = new DukascopyDownloader();
        // 2026-06-01 is a Monday
        LocalDate testDate = LocalDate.of(2026, 6, 1);
        Path csvPath = downloader.downloadRange("eurusd", testDate, testDate, "H1", dir);

        assertTrue(Files.exists(csvPath), "CSV file should be created");
        List<String> lines = Files.readAllLines(csvPath);
        assertFalse(lines.isEmpty(), "CSV should not be empty");
        assertTrue(lines.get(0).startsWith("timestamp,open,high,low,close"), "Header should be correct");
        
        // Assert we have multiple lines of data (usually 24 hours of H1 data on a weekday)
        assertTrue(lines.size() > 10, "Should have more than 10 lines of data");
    }
}
