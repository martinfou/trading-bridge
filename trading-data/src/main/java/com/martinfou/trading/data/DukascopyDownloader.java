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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class DukascopyDownloader {

    private static final Logger log = LoggerFactory.getLogger(DukascopyDownloader.class);

    private final HttpClient httpClient;
    private final Semaphore httpSemaphore = new Semaphore(50);

    public DukascopyDownloader() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public record Candle(long timestampMs, double open, double high, double low, double close) {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int completed, int total);
    }

    /**
     * Downloads data for a specific pair, year, and timeframe, and writes the CSV to outputDir.
     */
    public Path download(String pair, int year, String timeframe, Path outputDir) throws IOException {
        return download(pair, year, timeframe, outputDir, null);
    }

    public Path download(String pair, int year, String timeframe, Path outputDir, ProgressListener listener) throws IOException {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (end.isAfter(today)) {
            end = today;
        }
        return downloadRange(pair, start, end, timeframe, outputDir, listener);
    }

    /**
     * Downloads data for a specific pair and date range in parallel, and writes the CSV to outputDir.
     */
    public Path downloadRange(String pair, LocalDate start, LocalDate end, String timeframe, Path outputDir) throws IOException {
        return downloadRange(pair, start, end, timeframe, outputDir, null);
    }

    public Path downloadRange(String pair, LocalDate start, LocalDate end, String timeframe, Path outputDir, ProgressListener listener) throws IOException {
        String symbol = pair.toUpperCase().replace("_", "").replace("/", "");
        String tfLower = timeframe.toLowerCase();
        long barDurationMs = tfLower.equals("m1") ? 60 * 1000L : 3600 * 1000L;

        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        int totalTasks = (int) days * 24;
        java.util.concurrent.atomic.AtomicInteger completedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        log.info("Downloading Dukascopy data for {} ({}) from {} to {} in parallel ({} hours)", symbol, timeframe, start, end, totalTasks);

        List<CompletableFuture<List<Candle>>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            LocalDate current = start;
            while (!current.isAfter(end)) {
                for (int hour = 0; hour < 24; hour++) {
                    final LocalDate date = current;
                    final int h = hour;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            return downloadAndParseHour(symbol, date, h, barDurationMs);
                        } catch (Exception e) {
                            log.warn("Failed to download or parse data for date: {} Hour: {}. Error: {}", date, h, e.getMessage());
                            return Collections.emptyList();
                        } finally {
                            int completed = completedCount.incrementAndGet();
                            if (listener != null) {
                                listener.onProgress(completed, totalTasks);
                            }
                            if (completed % 1000 == 0 || completed == totalTasks) {
                                double pct = (completed * 100.0) / totalTasks;
                                log.info("  [{}] Download progress: {}/{} hours ({})", 
                                    symbol, completed, totalTasks, String.format(java.util.Locale.ROOT, "%.1f%%", pct));
                            }
                        }
                    }, executor));
                }
                current = current.plusDays(1);
            }
        } // try-with-resources auto-closes the executor, waiting for all virtual threads to complete

        // Collect candles chronologically
        List<Candle> allCandles = new ArrayList<>();
        for (var future : futures) {
            allCandles.addAll(future.join());
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

    private List<Candle> downloadAndParseHour(String symbol, LocalDate date, int hour, long barDurationMs) throws IOException, InterruptedException {
        // Dukascopy month is 0-indexed: 00 to 11
        String url = String.format("https://datafeed.dukascopy.com/datafeed/%s/%d/%02d/%02d/%02dh_ticks.bi5",
                symbol, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth(), hour);

        byte[] data = downloadFile(url);
        if (data == null || data.length == 0) {
            return Collections.emptyList();
        }

        long baseHourMs = LocalDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), hour, 0, 0)
                .toInstant(ZoneOffset.UTC).toEpochMilli();

        try (LZMAInputStream lzma = new LZMAInputStream(new ByteArrayInputStream(data))) {
            byte[] decompressed = lzma.readAllBytes();
            return parseTicksToCandles(decompressed, baseHourMs, barDurationMs);
        }
    }

    private List<Candle> parseTicksToCandles(byte[] decompressed, long baseHourMs, long barDurationMs) {
        List<Candle> hourCandles = new ArrayList<>();
        ByteBuffer wrap = ByteBuffer.wrap(decompressed);
        long currentBarStartMs = -1;
        double open = 0, high = 0, low = 0, close = 0;

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
                hourCandles.add(new Candle(currentBarStartMs, open, high, low, close));
                currentBarStartMs = barStartMs;
                open = bid;
                high = bid;
                low = bid;
                close = bid;
            }
        }
        if (currentBarStartMs != -1) {
            hourCandles.add(new Candle(currentBarStartMs, open, high, low, close));
        }
        return hourCandles;
    }

    byte[] downloadFile(String url) throws IOException, InterruptedException {
        httpSemaphore.acquire();
        try {
            int maxRetries = 3;
            long backoffMs = 500;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();

                    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() == 200) {
                        return response.body();
                    } else if (response.statusCode() == 404) {
                        // No data for this hour (weekend or holiday)
                        return null;
                    } else if (response.statusCode() == 429 || response.statusCode() == 503) {
                        log.warn("Rate limited (HTTP {}) for URL: {}, retrying in {}ms... (attempt {}/{})", 
                                response.statusCode(), url, backoffMs, attempt, maxRetries);
                    } else {
                        throw new IOException("HTTP status code: " + response.statusCode() + " for URL: " + url);
                    }
                } catch (IOException | InterruptedException e) {
                    if (attempt == maxRetries) {
                        throw e;
                    }
                    log.warn("Connection error for URL: {}, retrying in {}ms... (attempt {}/{}): {}", 
                            url, backoffMs, attempt, maxRetries, e.getMessage());
                }

                Thread.sleep(backoffMs);
                backoffMs *= 2; // exponential backoff
            }
            throw new IOException("Failed to download URL: " + url + " after " + maxRetries + " attempts");
        } finally {
            httpSemaphore.release();
        }
    }
}
