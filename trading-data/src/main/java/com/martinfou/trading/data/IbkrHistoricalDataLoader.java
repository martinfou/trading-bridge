package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses and loads historical bar data exported or retrieved from Interactive Brokers TWS API
 * (reqHistoricalData / historicalDataEnd responses) for Futures (MES, M2K, EMD, MNQ) and Equities (IWM, MDY, AAPL).
 */
public final class IbkrHistoricalDataLoader {

    private static final DateTimeFormatter IBKR_DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd  HH:mm:ss");
    private static final DateTimeFormatter IBKR_COMPACT_FMT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
    private static final DateTimeFormatter IBKR_DATE_ONLY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static final java.time.ZoneId DEFAULT_ZONE = java.time.ZoneId.of("America/New_York");

    private IbkrHistoricalDataLoader() {}

    public static List<Bar> loadCsv(Path path, String symbol) throws IOException {
        return loadCsv(path, symbol, DEFAULT_ZONE);
    }

    public static List<Bar> loadCsv(Path path, String symbol, java.time.ZoneId sourceZone) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return loadCsv(in, symbol, sourceZone);
        }
    }

    public static List<Bar> loadCsv(InputStream in, String symbol) throws IOException {
        return loadCsv(in, symbol, DEFAULT_ZONE);
    }

    public static List<Bar> loadCsv(InputStream in, String symbol, java.time.ZoneId sourceZone) throws IOException {
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // Header
            if (line == null) return List.of();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("[,;\t]");
                if (parts.length < 5) continue;

                try {
                    String timeStr = parts[0].trim();
                    Instant timestamp = parseIbkrTime(timeStr, sourceZone);
                    double open = Double.parseDouble(parts[1].trim());
                    double high = Double.parseDouble(parts[2].trim());
                    double low = Double.parseDouble(parts[3].trim());
                    double close = Double.parseDouble(parts[4].trim());
                    long volume = parts.length >= 6 ? (long) Double.parseDouble(parts[5].trim()) : 0L;

                    bars.add(new Bar(symbol, timestamp, open, high, low, close, volume));
                } catch (Exception ignored) {}
            }
        }
        return Collections.unmodifiableList(bars);
    }

    public static Instant parseIbkrTime(String timeStr) {
        return parseIbkrTime(timeStr, DEFAULT_ZONE);
    }

    public static Instant parseIbkrTime(String timeStr, java.time.ZoneId sourceZone) {
        if (timeStr == null || timeStr.isBlank()) {
            return Instant.EPOCH;
        }
        String clean = timeStr.trim();
        if (clean.contains("T") || clean.contains("Z")) {
            return Instant.parse(clean);
        }
        try {
            long epoch = Long.parseLong(clean);
            return epoch > 1_000_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException ignored) {}

        java.time.ZoneId zone = sourceZone != null ? sourceZone : DEFAULT_ZONE;

        if (clean.contains("  ")) {
            LocalDateTime ldt = LocalDateTime.parse(clean, IBKR_DATETIME_FMT);
            return ldt.atZone(zone).toInstant();
        }
        if (clean.contains(" ")) {
            LocalDateTime ldt = LocalDateTime.parse(clean, IBKR_COMPACT_FMT);
            return ldt.atZone(zone).toInstant();
        }
        if (clean.length() == 8) {
            LocalDateTime ldt = LocalDateTime.parse(clean + " 00:00:00", IBKR_COMPACT_FMT);
            return ldt.atZone(zone).toInstant();
        }
        return Instant.EPOCH;
    }
}

