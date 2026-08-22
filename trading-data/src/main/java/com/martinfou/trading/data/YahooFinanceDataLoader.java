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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ingests free historical Daily and H1 price bars from Yahoo Finance CSV / feeds
 * for CME Futures continuous proxies (ES=F, RTY=F, NQ=F) and US Equities (IWM, MDY, AAPL, SPY, QQQ).
 */
public final class YahooFinanceDataLoader {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private YahooFinanceDataLoader() {}

    public static List<Bar> loadCsv(Path csvPath, String targetSymbol) throws IOException {
        try (InputStream in = Files.newInputStream(csvPath)) {
            return loadCsv(in, targetSymbol);
        }
    }

    public static List<Bar> loadCsv(InputStream in, String targetSymbol) throws IOException {
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            if (line == null) return List.of();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] tokens = line.split(",");
                if (tokens.length < 6) continue;

                try {
                    String dateStr = tokens[0].trim();
                    Instant timestamp;
                    if (dateStr.contains("T") || dateStr.contains("Z")) {
                        timestamp = Instant.parse(dateStr);
                    } else if (dateStr.length() == 10) {
                        LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
                        timestamp = date.atStartOfDay(ZoneOffset.UTC).toInstant();
                    } else {
                        long epoch = Long.parseLong(dateStr);
                        timestamp = epoch > 1_000_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
                    }

                    double open = Double.parseDouble(tokens[1].trim());
                    double high = Double.parseDouble(tokens[2].trim());
                    double low = Double.parseDouble(tokens[3].trim());
                    double close = Double.parseDouble(tokens[4].trim());
                    // tokens[5] is Adj Close
                    long volume = tokens.length >= 7 ? parseLongSafe(tokens[6].trim()) : parseLongSafe(tokens[5].trim());

                    bars.add(new Bar(targetSymbol, timestamp, open, high, low, close, volume));
                } catch (Exception ignored) {
                    // skip invalid/null lines (e.g. market holidays)
                }
            }
        }
        return Collections.unmodifiableList(bars);
    }

    public static String mapProxySymbol(String yahooTicker) {
        if (yahooTicker == null) return "EUR_USD";
        String upper = yahooTicker.trim().toUpperCase();
        return switch (upper) {
            case "ES=F" -> "MES";
            case "RTY=F", "M2K=F" -> "M2K";
            case "NQ=F" -> "MNQ";
            case "EMD=F" -> "EMD";
            default -> upper.replace('=', '_').replace('/', '_');
        };
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(s);
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }
}
