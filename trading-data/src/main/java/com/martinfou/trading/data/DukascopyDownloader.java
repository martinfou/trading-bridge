package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.BarAggregator;
import com.martinfou.trading.core.CmeGlobexMarketCalendar;
import com.martinfou.trading.core.FuturesContract;
import com.martinfou.trading.core.FuturesRegistry;
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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class DukascopyDownloader {

    private static final Logger log = LoggerFactory.getLogger(DukascopyDownloader.class);

    private final HttpClient httpClient;
    private final Semaphore httpSemaphore = new Semaphore(3);

    public DukascopyDownloader() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public record Candle(long timestampMs, double open, double high, double low, double close, long volume) {
        public Candle(long timestampMs, double open, double high, double low, double close) {
            this(timestampMs, open, high, low, close, 0L);
        }
    }

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
        if (pair == null || pair.isBlank() || timeframe == null || timeframe.isBlank()) {
            throw new IllegalArgumentException("pair and timeframe are required");
        }
        String symbol = toDukascopyInstrument(pair);
        String filePrefix = pair.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("/", "");
        String tfLower = timeframe.toLowerCase(java.util.Locale.ROOT);

        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        int totalTasks = (int) days;
        java.util.concurrent.atomic.AtomicInteger completedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        log.info("Downloading Dukascopy data for {} (feed={}) ({}) from {} to {} in parallel ({} days)",
            pair, symbol, timeframe, start, end, totalTasks);

        List<LocalDate> targetDates = new ArrayList<>();
        LocalDate current = start;
        while (!current.isAfter(end)) {
            targetDates.add(current);
            current = current.plusDays(1);
        }

        java.util.concurrent.ConcurrentHashMap<LocalDate, List<Candle>> successfulResults = new java.util.concurrent.ConcurrentHashMap<>();
        List<LocalDate> pendingDates = new ArrayList<>(targetDates);

        int maxPasses = 3;
        for (int pass = 1; pass <= maxPasses && !pendingDates.isEmpty(); pass++) {
            if (pass > 1) {
                log.info("Starting retry pass {}/{} for {} failed days of symbol {}", pass, maxPasses, pendingDates.size(), symbol);
                try {
                    Thread.sleep(5000L * (pass - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download range interrupted during backoff", e);
                }
            }

            final int currentPass = pass;
            List<LocalDate> currentBatch = new ArrayList<>(pendingDates);
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (LocalDate date : currentBatch) {
                    batchFutures.add(CompletableFuture.runAsync(() -> {
                        try {
                            List<Candle> dayCandles = downloadAndParseDay(symbol, date);
                            successfulResults.put(date, dayCandles);
                            
                            int completed = completedCount.incrementAndGet();
                            if (listener != null) {
                                listener.onProgress(completed, totalTasks);
                            }
                            if (completed % 10 == 0 || completed == totalTasks) {
                                double pct = (completed * 100.0) / totalTasks;
                                log.info("  [{}] Download progress: {}/{} days ({})", 
                                    symbol, completed, totalTasks, String.format(java.util.Locale.ROOT, "%.1f%%", pct));
                            }
                        } catch (Exception e) {
                            log.warn("Failed to download or parse data for date: {} in pass {}. Error: {}", date, currentPass, e.getMessage());
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }, executor));
                }
            } // auto-closes executor, waiting for virtual threads in this pass to finish

            pendingDates.removeIf(successfulResults::containsKey);
        }

        if (!pendingDates.isEmpty()) {
            throw new IOException("Failed to download data for " + pendingDates.size() + " dates after " + maxPasses + " passes. Failed dates: " + pendingDates);
        }

        // Collect candles chronologically
        List<Candle> allCandles = new ArrayList<>();
        LocalDate sortCurrent = start;
        while (!sortCurrent.isAfter(end)) {
            List<Candle> dayCandles = successfulResults.get(sortCurrent);
            if (dayCandles != null) {
                allCandles.addAll(dayCandles);
            }
            sortCurrent = sortCurrent.plusDays(1);
        }

        if (allCandles.isEmpty()) {
            throw new IOException("No data downloaded for " + symbol + " in range " + start + " to " + end);
        }

        // Sort candles chronologically to be robust
        allCandles.sort(java.util.Comparator.comparingLong(Candle::timestampMs));

        Optional<FuturesContract> futuresContract = FuturesRegistry.find(pair);
        if (futuresContract.isPresent()) {
            allCandles = filterGlobexTradingMinutes(allCandles, futuresContract.get());
        }

        // Aggregate 1-minute candles into requested timeframe if coarser than M1
        long bucketMs = timeframeBucketMs(tfLower);
        if (bucketMs >= 4 * 3_600_000L) {
            allCandles = aggregateViaBarAggregator(allCandles, pair, tfLower.toUpperCase(java.util.Locale.ROOT));
        } else if (bucketMs > 60_000L) {
            allCandles = aggregateToBucket(allCandles, bucketMs);
        }

        if (futuresContract.isPresent()) {
            allCandles = quantizeFuturesCandles(allCandles, futuresContract.get());
        }

        // Save as CSV
        String filename = String.format("%s-%s-bid-%s-%s.csv",
                filePrefix, tfLower,
                DateTimeFormatter.ofPattern("yyyy-MM-dd").format(start),
                DateTimeFormatter.ofPattern("yyyy-MM-dd").format(end));
        Path csvPath = outputDir.resolve(filename);
        Files.createDirectories(outputDir);

        try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
            writer.write("timestamp,open,high,low,close,volume\n");
            for (Candle c : allCandles) {
                writer.write(String.format(java.util.Locale.ROOT, "%d,%.5f,%.5f,%.5f,%.5f,%d\n",
                        c.timestampMs(), c.open(), c.high(), c.low(), c.close(), c.volume()));
            }
        }

        log.info("Saved raw CSV to: {}", csvPath);
        return csvPath;
    }

    /**
     * Filters 1-minute Dukascopy proxy candles so their session schedule matches IBKR CME/COMEX Globex futures:
     * <ul>
     *   <li>Timezone: {@code America/New_York} (automatically adjusts for US DST transitions)</li>
     *   <li>Sunday: Opens at 18:00 ET</li>
     *   <li>Monday–Thursday: Daily maintenance halt 17:00–18:00 ET (plus 16:15–16:30 ET pause on CME equity index futures)</li>
     *   <li>Friday: Closes at 17:00 ET</li>
     *   <li>Saturday: Closed all day</li>
     *   <li>Also drops zero-volume flat maintenance-padding bars when positive-volume bars exist</li>
     * </ul>
     */
    static List<Candle> filterGlobexTradingMinutes(List<Candle> candles, FuturesContract contract) {
        if (candles.isEmpty()) {
            return candles;
        }
        boolean isCmeEquityIndex = "CME".equalsIgnoreCase(contract.exchange());
        boolean hasPositiveVolume = false;
        for (Candle c : candles) {
            if (c.volume() > 0L) {
                hasPositiveVolume = true;
                break;
            }
        }
        List<Candle> filtered = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            if (hasPositiveVolume
                && c.volume() <= 0L
                && Double.compare(c.open(), c.high()) == 0
                && Double.compare(c.high(), c.low()) == 0
                && Double.compare(c.low(), c.close()) == 0) {
                continue;
            }
            if (isGlobexSessionOpen(Instant.ofEpochMilli(c.timestampMs()), isCmeEquityIndex)) {
                filtered.add(c);
            }
        }
        return filtered;
    }

    /**
     * Checks whether CME/COMEX Globex is open at {@code instant} in {@code America/New_York}.
     * COMEX/NYMEX/CBOT contracts (e.g. MGC, GC, MCL, MYM) trade continuously 18:00–17:00 ET without the
     * 16:15–16:30 ET equity-index settlement pause, whereas CME equity index futures pause 16:15–16:30 ET.
     */
    public static boolean isGlobexSessionOpen(Instant instant, boolean includeCmeEquityPause) {
        if (includeCmeEquityPause) {
            return CmeGlobexMarketCalendar.isTradingTime(instant);
        }
        if (instant == null) {
            return false;
        }
        ZonedDateTime et = instant.atZone(CmeGlobexMarketCalendar.EASTERN_ZONE);
        DayOfWeek dow = et.getDayOfWeek();
        int timeInMinutes = et.getHour() * 60 + et.getMinute();
        if (dow == DayOfWeek.SATURDAY) {
            return false;
        }
        if (dow == DayOfWeek.SUNDAY) {
            return timeInMinutes >= 18 * 60;
        }
        if (dow == DayOfWeek.FRIDAY) {
            return timeInMinutes < 17 * 60;
        }
        // Monday–Thursday: closed only during 17:00–18:00 ET maintenance halt
        return !(timeInMinutes >= 17 * 60 && timeInMinutes < 18 * 60);
    }

    /**
     * Quantizes OHLC prices of each candle to the futures contract's exact exchange {@code minTick}
     * (e.g. 0.10 for MGC/M2K, 0.25 for MES/MNQ, 1.00 for MYM) while preserving OHLC ordering invariants.
     */
    static List<Candle> quantizeFuturesCandles(List<Candle> candles, FuturesContract contract) {
        if (candles.isEmpty() || contract == null) {
            return candles;
        }
        List<Candle> quantized = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            double o = contract.quantizePrice(c.open());
            double cl = contract.quantizePrice(c.close());
            double h = Math.max(contract.quantizePrice(c.high()), Math.max(o, cl));
            double l = Math.min(contract.quantizePrice(c.low()), Math.min(o, cl));
            quantized.add(new Candle(c.timestampMs(), o, h, l, cl, c.volume()));
        }
        return quantized;
    }

    /**
     * Maps a trading-bridge symbol or pair (Forex, Metals, or US Index Future) to its Dukascopy instrument code.
     */
    public static String toDukascopyInstrument(String symbolOrPair) {
        if (symbolOrPair == null || symbolOrPair.isBlank()) {
            return "EURUSD";
        }
        String norm = symbolOrPair.trim().toUpperCase(java.util.Locale.ROOT).replace("_", "").replace("/", "");
        return switch (norm) {
            case "MGC", "GC", "XAUUSD" -> "XAUUSD";
            case "SI", "SIL", "XAGUSD" -> "XAGUSD";
            case "MES", "ES", "USA500IDXUSD" -> "USA500IDXUSD";
            case "MNQ", "NQ", "USATECHIDXUSD" -> "USATECHIDXUSD";
            case "M2K", "RTY", "USA2000IDXUSD" -> "USA2000IDXUSD";
            case "MYM", "YM", "US30IDXUSD" -> "US30IDXUSD";
            default -> norm;
        };
    }

    /**
     * True if Dukascopy provides historical M1/intraday BI5 feeds for the given symbol
     * (standard FX pairs, spot metals, and major US equity index futures proxies).
     */
    public static boolean supportsSymbol(String symbolOrPair) {
        if (symbolOrPair == null || symbolOrPair.isBlank()) {
            return false;
        }
        String clean = symbolOrPair.trim().toUpperCase(java.util.Locale.ROOT).replace("_", "").replace("/", "");
        if (clean.matches("(EUR|USD|GBP|JPY|AUD|NZD|CAD|CHF){2}")
            && !clean.substring(0, 3).equals(clean.substring(3, 6))) {
            return true;
        }
        return switch (clean) {
            case "MGC", "GC", "XAUUSD",
                 "SI", "SIL", "XAGUSD",
                 "MES", "ES", "USA500IDXUSD",
                 "MNQ", "NQ", "USATECHIDXUSD",
                 "M2K", "RTY", "USA2000IDXUSD",
                 "MYM", "YM", "US30IDXUSD" -> true;
            default -> false;
        };
    }

    /**
     * Returns the integer price divisor used by Dukascopy BI5 files for the given Dukascopy instrument.
     * JPY pairs, precious metals (XAUUSD/XAGUSD), and equity indices (*IDX*) use 1,000 (10^3);
     * standard 5-decimal FX pairs use 100,000 (10^5).
     */
    public static double priceScaleForSymbol(String dukascopySymbol) {
        if (dukascopySymbol == null) {
            return 100_000.0;
        }
        String upper = dukascopySymbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (upper.endsWith("JPY")
            || upper.startsWith("XAU")
            || upper.startsWith("XAG")
            || upper.contains("IDX")) {
            return 1_000.0;
        }
        return 100_000.0;
    }

    static long timeframeBucketMs(String tfLower) {
        if (tfLower == null || tfLower.isBlank()) {
            throw new IllegalArgumentException("Blank timeframe");
        }
        return switch (tfLower.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "m1", "1m" -> 60_000L;
            case "m5", "5m" -> 5 * 60_000L;
            case "m15", "15m" -> 15 * 60_000L;
            case "m30", "30m" -> 30 * 60_000L;
            case "h1", "1h", "60m" -> 3_600_000L;
            case "h4", "4h" -> 4 * 3_600_000L;
            case "d1", "1d" -> 24 * 3_600_000L;
            case "w1", "1w" -> 7 * 24 * 3_600_000L;
            default -> throw new IllegalArgumentException("Unsupported timeframe: " + tfLower);
        };
    }

    private static List<Candle> aggregateViaBarAggregator(List<Candle> candles, String symbol, String targetTf) {
        if (candles.isEmpty()) {
            return Collections.emptyList();
        }
        String stdTf = switch (targetTf) {
            case "4H" -> "H4";
            case "1D" -> "D1";
            case "1W" -> "W1";
            default -> targetTf;
        };
        BarAggregator agg = new BarAggregator(symbol, stdTf);
        List<Candle> result = new ArrayList<>();
        for (Candle c : candles) {
            Bar b = new Bar(symbol, Instant.ofEpochMilli(c.timestampMs()), c.open(), c.high(), c.low(), c.close(), c.volume());
            boolean newPeriod = agg.isNewPeriod(b);
            agg.add(b);
            if (newPeriod) {
                agg.getLastCompletedBar().ifPresent(done ->
                    result.add(new Candle(done.timestamp().toEpochMilli(), done.open(), done.high(), done.low(), done.close(), done.volume()))
                );
            }
        }
        agg.completePeriod();
        agg.getLastCompletedBar().ifPresent(done ->
            result.add(new Candle(done.timestamp().toEpochMilli(), done.open(), done.high(), done.low(), done.close(), done.volume()))
        );
        return result;
    }

    private List<Candle> downloadAndParseDay(String symbol, LocalDate date) throws IOException, InterruptedException {
        // Dukascopy month is 0-indexed: 00 to 11
        String url = String.format("https://datafeed.dukascopy.com/datafeed/%s/%d/%02d/%02d/BID_candles_min_1.bi5",
                symbol, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());

        byte[] data = downloadFile(url);
        if (data == null || data.length == 0) {
            return Collections.emptyList();
        }

        long baseDayStartMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        double scale = priceScaleForSymbol(symbol);

        try (LZMAInputStream lzma = new LZMAInputStream(new ByteArrayInputStream(data))) {
            byte[] decompressed = lzma.readAllBytes();
            return parseCandles(decompressed, baseDayStartMs, scale);
        }
    }

    private List<Candle> parseCandles(byte[] decompressed, long baseDayStartMs, double scale) {
        List<Candle> dayCandles = new ArrayList<>();
        ByteBuffer wrap = ByteBuffer.wrap(decompressed);

        while (wrap.remaining() >= 24) {
            int timeOffsetSec = wrap.getInt();
            int openInt = wrap.getInt();
            int closeInt = wrap.getInt();
            int lowInt = wrap.getInt();
            int highInt = wrap.getInt();
            float vol = wrap.getFloat();

            long barTimeMs = baseDayStartMs + (timeOffsetSec * 1000L);
            double open = openInt / scale;
            double close = closeInt / scale;
            double low = lowInt / scale;
            double high = highInt / scale;
            long volume = Float.isFinite(vol) && vol > 0f ? Math.max(1L, Math.round(vol * 1000.0)) : 0L;

            dayCandles.add(new Candle(barTimeMs, open, high, low, close, volume));
        }
        return dayCandles;
    }

    private List<Candle> aggregateToBucket(List<Candle> minCandles, long bucketMs) {
        if (minCandles.isEmpty()) {
            return Collections.emptyList();
        }

        List<Candle> bucketed = new ArrayList<>();
        long currentBucketStartMs = -1;
        double open = 0, high = 0, low = 0, close = 0;
        long volumeSum = 0L;

        for (Candle c : minCandles) {
            long bucketStartMs = (c.timestampMs() / bucketMs) * bucketMs;
            if (currentBucketStartMs == -1) {
                currentBucketStartMs = bucketStartMs;
                open = c.open();
                high = c.high();
                low = c.low();
                close = c.close();
                volumeSum = c.volume();
            } else if (bucketStartMs == currentBucketStartMs) {
                high = Math.max(high, c.high());
                low = Math.min(low, c.low());
                close = c.close();
                volumeSum += c.volume();
            } else {
                bucketed.add(new Candle(currentBucketStartMs, open, high, low, close, volumeSum));
                currentBucketStartMs = bucketStartMs;
                open = c.open();
                high = c.high();
                low = c.low();
                close = c.close();
                volumeSum = c.volume();
            }
        }
        if (currentBucketStartMs != -1) {
            bucketed.add(new Candle(currentBucketStartMs, open, high, low, close, volumeSum));
        }
        return bucketed;
    }

    byte[] downloadFile(String url) throws IOException, InterruptedException {
        httpSemaphore.acquire();
        try {
            Thread.sleep(100);
            int maxRetries = 5;
            long backoffMs = 1000;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .timeout(Duration.ofSeconds(15))
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
