package com.martinfou.trading.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DukascopyDownloaderTest {

    @Test
    void downloadRange_downloadsAndParsesData(@TempDir Path dir) throws Exception {
        DukascopyDownloader downloader = new DukascopyDownloader() {
            @Override
            byte[] downloadFile(String url) {
                try {
                    // Generate 1440 dummy minutes for a full day (24 bytes: timeOffsetSec, open, close, low, high, vol)
                    ByteArrayOutputStream candleBuf = new ByteArrayOutputStream();
                    java.io.DataOutputStream dos = new java.io.DataOutputStream(candleBuf);
                    for (int min = 0; min < 1440; min++) {
                        dos.writeInt(min * 60); // timeOffsetSec
                        dos.writeInt(105000);   // open = 1.05000
                        dos.writeInt(104000);   // close = 1.04000
                        dos.writeInt(103000);   // low = 1.03000
                        dos.writeInt(106000);   // high = 1.06000
                        dos.writeFloat(1.0f);   // volume
                    }
                    dos.flush();
                    byte[] raw = candleBuf.toByteArray();

                    // Compress to LZMA format using the org.tukaani.xz library
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    org.tukaani.xz.LZMA2Options options = new org.tukaani.xz.LZMA2Options();
                    try (org.tukaani.xz.LZMAOutputStream lzma = new org.tukaani.xz.LZMAOutputStream(out, options, raw.length)) {
                        lzma.write(raw);
                    }
                    return out.toByteArray();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        // 2026-06-01 is a Monday
        LocalDate testDate = LocalDate.of(2026, 6, 1);
        Path csvPath = downloader.downloadRange("eurusd", testDate, testDate, "H1", dir);

        assertTrue(Files.exists(csvPath), "CSV file should be created");
        List<String> lines = Files.readAllLines(csvPath);
        assertFalse(lines.isEmpty(), "CSV should not be empty");
        assertTrue(lines.get(0).startsWith("timestamp,open,high,low,close"), "Header should be correct");
        
        // We expect 24 lines of data (1 for each hour) + 1 header line = 25 lines
        assertTrue(lines.size() >= 24, "Should have 24 lines of data");
    }
}

