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
}
