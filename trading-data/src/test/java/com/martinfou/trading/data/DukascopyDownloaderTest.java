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

    @Test
    void downloadRange_retriesFailedDays(@TempDir Path dir) throws Exception {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        DukascopyDownloader downloader = new DukascopyDownloader() {
            @Override
            byte[] downloadFile(String url) throws java.io.IOException {
                int count = callCount.incrementAndGet();
                if (count == 1) {
                    throw new java.io.IOException("Simulated transient connection error on first call");
                }
                try {
                    // Generate 1440 dummy minutes
                    ByteArrayOutputStream candleBuf = new ByteArrayOutputStream();
                    java.io.DataOutputStream dos = new java.io.DataOutputStream(candleBuf);
                    for (int min = 0; min < 1440; min++) {
                        dos.writeInt(min * 60);
                        dos.writeInt(105000);
                        dos.writeInt(104000);
                        dos.writeInt(103000);
                        dos.writeInt(106000);
                        dos.writeFloat(1.0f);
                    }
                    dos.flush();
                    byte[] raw = candleBuf.toByteArray();

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

        LocalDate testDate = LocalDate.of(2026, 6, 1);
        Path csvPath = downloader.downloadRange("eurusd", testDate, testDate, "H1", dir);

        assertTrue(Files.exists(csvPath), "CSV file should be created");
        List<String> lines = Files.readAllLines(csvPath);
        assertTrue(lines.size() >= 24, "Should have successfully downloaded on retry");
        assertTrue(callCount.get() >= 2, "Should have been called at least twice");
    }

    @Test
    void downloadRange_mapsFuturesToDukascopyProxyAndAggregatesM30(@TempDir Path dir) throws Exception {
        java.util.List<String> requestedUrls = new java.util.concurrent.CopyOnWriteArrayList<>();
        DukascopyDownloader downloader = new DukascopyDownloader() {
            @Override
            byte[] downloadFile(String url) {
                requestedUrls.add(url);
                try {
                    // Generate 1440 minutes of Gold with unquantized 3-decimal prices (e.g. 2650.098 -> snaps to 2650.10 on MGC 0.10 tick)
                    ByteArrayOutputStream candleBuf = new ByteArrayOutputStream();
                    java.io.DataOutputStream dos = new java.io.DataOutputStream(candleBuf);
                    for (int min = 0; min < 1440; min++) {
                        dos.writeInt(min * 60);
                        dos.writeInt(2_650_098); // 2650.098 -> 2650.10
                        dos.writeInt(2_652_542); // 2652.542 -> 2652.50
                        dos.writeInt(2_648_012); // 2648.012 -> 2648.00
                        dos.writeInt(2_655_089); // 2655.089 -> 2655.10
                        dos.writeFloat(10.0f);   // 10,000 per min -> 300,000 per M30 bar
                    }
                    dos.flush();
                    byte[] raw = candleBuf.toByteArray();

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

        org.junit.jupiter.api.Assertions.assertEquals("XAUUSD", DukascopyDownloader.toDukascopyInstrument("MGC"));
        org.junit.jupiter.api.Assertions.assertEquals("USA500IDXUSD", DukascopyDownloader.toDukascopyInstrument("MES"));
        org.junit.jupiter.api.Assertions.assertEquals("USATECHIDXUSD", DukascopyDownloader.toDukascopyInstrument("MNQ"));
        org.junit.jupiter.api.Assertions.assertEquals("USA2000IDXUSD", DukascopyDownloader.toDukascopyInstrument("M2K"));
        assertTrue(DukascopyDownloader.supportsSymbol("MGC"));
        assertTrue(DukascopyDownloader.supportsSymbol("MES"));
        assertFalse(DukascopyDownloader.supportsSymbol("EMD"), "EMD should not map to S&P 500");
        assertFalse(DukascopyDownloader.supportsSymbol("SPY"), "ETFs should not use 23h Globex schedule");
        assertFalse(DukascopyDownloader.supportsSymbol("USDUSD"), "Self-paired currency must be rejected");
        assertFalse(DukascopyDownloader.supportsSymbol("NVDA"), "Single-stock equities not in Dukascopy FX/Index set");

        // 2020-06-01 is a Monday (EDT = UTC-4; 17:00-18:00 ET maintenance halt is 21:00-22:00 UTC = 2 M30 bars dropped)
        LocalDate testDate = LocalDate.of(2020, 6, 1);
        Path csvPath = downloader.downloadRange("MGC", testDate, testDate, "M30", dir);

        assertTrue(Files.exists(csvPath));
        assertTrue(requestedUrls.get(0).contains("/XAUUSD/"), "MGC should query Dukascopy XAUUSD feed");
        List<String> lines = Files.readAllLines(csvPath);
        // 1 header + 46 M30 candles (23h Globex session excluding 17:00-18:00 ET maintenance break) = 47 lines
        org.junit.jupiter.api.Assertions.assertEquals(47, lines.size());
        assertTrue(lines.get(0).equals("timestamp,open,high,low,close,volume"));
        assertTrue(lines.get(1).contains("2650.10000,2655.10000,2648.00000,2652.50000,300000"),
            "Prices must be quantized to MGC 0.10 tick and volume summed across 30 mins, got: " + lines.get(1));
    }
}

