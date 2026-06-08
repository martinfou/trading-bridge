package com.martinfou.trading.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.LZMAInputStream;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class DukascopyDownloader {

    private static final Logger log = LoggerFactory.getLogger(DukascopyDownloader.class);

    private final HttpClient httpClient;

    public DukascopyDownloader() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record Candle(long timestampMs, double open, double high, double low, double close) {}

    /**
     * Downloads data for a specific pair, year, and timeframe, and writes the CSV to outputDir.
     */
    public Path download(String pair, int year, String timeframe, Path outputDir) throws IOException {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (end.isAfter(today)) {
            end = today;
        }
        return downloadRange(pair, start, end, timeframe, outputDir);
    }

    /**
     * Downloads data for a specific pair and date range, and writes the CSV to outputDir.
     */
    public Path downloadRange(String pair, LocalDate start, LocalDate end, String timeframe, Path outputDir) throws IOException {
        String symbol = pair.toUpperCase().replace("_", "").replace("/", "");
        String tfLower = timeframe.toLowerCase();
        long barDurationMs = tfLower.equals("m1") ? 60 * 1000L : 3600 * 1000L;

        List<Candle> allCandles = new ArrayList<>();
        log.info("Downloading Dukascopy data for {} ({}) from {} to {}", symbol, timeframe, start, end);

        long currentBarStartMs = -1;
        double open = 0, high = 0, low = 0, close = 0;

        LocalDate current = start;
        while (!current.isAfter(end)) {
            for (int hour = 0; hour < 24; hour++) {
                // Dukascopy month is 0-indexed: 00 to 11
                String url = String.format("https://datafeed.dukascopy.com/datafeed/%s/%d/%02d/%02d/%02dh_ticks.bi5",
                        symbol, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth(), hour);

                try {
                    byte[] data = downloadFile(url);
                    if (data != null && data.length > 0) {
                        long baseHourMs = LocalDateTime.of(current.getYear(), current.getMonthValue(), current.getDayOfMonth(), hour, 0, 0)
                                .toInstant(ZoneOffset.UTC).toEpochMilli();

                        try (LZMAInputStream lzma = new LZMAInputStream(new ByteArrayInputStream(data))) {
                            byte[] decompressed = lzma.readAllBytes();
                            ByteBuffer wrap = ByteBuffer.wrap(decompressed);

                            while (wrap.remaining() >= 20) {
                                int timeOffsetMs = wrap.getInt();
                                double ask = wrap.getInt() / 100000.0;
                                double bid = wrap.getInt() / 100000.0;
                                float askVol = wrap.getFloat();
                                float bidVol = wrap.getFloat();

                                long tickTimeMs = baseHourMs + timeOffsetMs;
                                long barStartMs = (tickTimeMs / barDurationMs) * barDurationMs;

                                if (currentBarStartMs == -1) {
                                    currentBarStartMs = barStartMs;
                                    open = bid;
                                    high = bid;
                                    low = bid;
                                    close = bid;
                                } else if (barStartMs == currentBarStartMs) {
                                    high = Math.max(high, bid);
                                    low = Math.min(low, bid);
                                    close = bid;
                                } else {
                                    allCandles.add(new Candle(currentBarStartMs, open, high, low, close));
                                    currentBarStartMs = barStartMs;
                                    open = bid;
                                    high = bid;
                                    low = bid;
                                    close = bid;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to download or parse data for date: {} Hour: {} - URL: {}. Error: {}", current, hour, url, e.getMessage());
                }
            }
            current = current.plusDays(1);
        }

        if (currentBarStartMs != -1) {
            allCandles.add(new Candle(currentBarStartMs, open, high, low, close));
        }

        if (allCandles.isEmpty()) {
            throw new IOException("No data downloaded for " + symbol + " in range " + start + " to " + end);
        }

        // Save as CSV
        String filename = String.format("%s-%s-bid-%s-%s.csv",
                pair.toLowerCase(), tfLower,
                DateTimeFormatter.ofPattern("yyyy-MM-dd").format(start),
                DateTimeFormatter.ofPattern("yyyy-MM-dd").format(end));
        Path csvPath = outputDir.resolve(filename);
        Files.createDirectories(outputDir);

        try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
            writer.write("timestamp,open,high,low,close\n");
            for (Candle c : allCandles) {
                writer.write(String.format("%d,%.5f,%.5f,%.5f,%.5f\n",
                        c.timestampMs(), c.open(), c.high(), c.low(), c.close()));
            }
        }

        log.info("Saved raw CSV to: {}", csvPath);
        return csvPath;
    }

    private byte[] downloadFile(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 200) {
            return response.body();
        } else if (response.statusCode() == 404) {
            // No data for this hour (weekend or holiday)
            return null;
        } else {
            throw new IOException("HTTP status code: " + response.statusCode() + " for URL: " + url);
        }
    }
}
