package com.martinfou.trading.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads OHLCV bars from CSV. Timestamps in files are interpreted as UTC wall-clock
 * (see {@link TimeConventions#csvLocalAsUtc} and docs/specs.md §2.5).
 *
 * <h3>Supported formats (auto-detected)</h3>
 * <ul>
 *   <li><b>Dukascopy</b> — header: {@code timestamp,open,high,low,close[,volume]}
 *       (timestamp in epoch ms)</li>
 *   <li><b>StrategyQuant</b> — header: {@code Date,Time,Open,High,Low,Close,Volume}</li>
 *   <li><b>Generic</b> — header: {@code timestamp,open,high,low,close,volume}
 *       (timestamp in ISO-8601 or similar)</li>
 * </ul>
 */
public class DataLoader {

    public static List<Bar> loadCSV(Path path, String symbol) {
        List<Bar> bars = new ArrayList<>();
        String line;
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            br.readLine();
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 6) continue;
                try {
                    Instant ts = TimeConventions.parseCsvTimestamp(parts[0].trim());
                    double open = Double.parseDouble(parts[1].trim());
                    double high = Double.parseDouble(parts[2].trim());
                    double low = Double.parseDouble(parts[3].trim());
                    double close = Double.parseDouble(parts[4].trim());
                    long volume = Long.parseLong(parts[5].trim());
                    bars.add(new Bar(symbol, ts, open, high, low, close, volume));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Error loading data: " + e.getMessage());
        }
        return bars;
    }

    public static List<Bar> loadStrategyQuantCSV(Path path, String symbol) {
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            br.readLine();
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 7) continue;
                try {
                    Instant ts = TimeConventions.parseCsvTimestamp(p[0].trim() + "T" + p[1].trim());
                    bars.add(new Bar(symbol, ts,
                        Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                        Long.parseLong(p[6])));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Error loading SQ data: " + e.getMessage());
        }
        return bars;
    }

    /**
     * Loads Dukascopy CSV format: timestamp_ms,open,high,low,close[,volume].
     * Falls back to SQ format if Dukascopy parsing yields zero bars.
     */
    public static List<Bar> loadDukascopyCSV(Path path, String symbol) {
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String header = br.readLine();
            boolean hasVolume = header != null && header.split(",").length >= 6;
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 5) continue;
                try {
                    long tsMs = Long.parseLong(parts[0].trim());
                    Instant ts = Instant.ofEpochMilli(tsMs);
                    double open = Double.parseDouble(parts[1].trim());
                    double high = Double.parseDouble(parts[2].trim());
                    double low = Double.parseDouble(parts[3].trim());
                    double close = Double.parseDouble(parts[4].trim());
                    long volume = hasVolume && parts.length >= 6 ? Long.parseLong(parts[5].trim()) : 0;
                    bars.add(new Bar(symbol, ts, open, high, low, close, volume));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Error loading Dukascopy data: " + e.getMessage());
        }

        // Fallback: try SQ format if Dukascopy returned nothing
        if (bars.isEmpty()) {
            System.err.println("Dukascopy format failed, trying StrategyQuant format...");
            return loadStrategyQuantCSV(path, symbol);
        }
        return bars;
    }

    /**
     * Auto-detects CSV format by inspecting the first line header and dispatches
     * to the appropriate loader. Returns SQ loader as fallback.
     */
    public static List<Bar> loadAuto(Path path, String symbol) {
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String header = br.readLine();
            if (header != null) {
                String lower = header.toLowerCase().trim();
                // Dukascopy: first column is "timestamp" (or epoch ms in first data row)
                if (lower.startsWith("timestamp")) {
                    return loadDukascopyCSV(path, symbol);
                }
            }
        } catch (Exception ignored) {}
        // Default: try SQ format first, fall back to generic CSV
        List<Bar> bars = loadStrategyQuantCSV(path, symbol);
        if (bars.isEmpty()) {
            bars = loadCSV(path, symbol);
        }
        return bars;
    }

    /**
     * Returns an appropriate quote→USD rate for non-USD pairs,
     * or {@code null} if the pair is already quoted in USD.
     *
     * @param symbol instrument symbol (e.g. "EUR/USD", "GBP/JPY", "USD/CAD")
     * @return quote→USD rate, or null if no conversion needed
     */
    public static Double detectQuoteToUsdRate(String symbol) {
        String normalized = symbol.replace('_', '/');
        int slash = normalized.indexOf('/');
        if (slash < 0) return null;
        String quote = normalized.substring(slash + 1);
        if ("USD".equalsIgnoreCase(quote)) return null;
        // JPY pairs default to ~95 USD/JPY for 2026
        if ("JPY".equalsIgnoreCase(quote)) return 95.0;
        // Other non-USD quotes — caller must provide explicit rate
        System.err.println("WARNING: Unknown quote currency " + quote
            + " for " + symbol + ". Pass explicit --quote-rate.");
        return 1.0;
    }
}
