package com.martinfou.trading.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import com.martinfou.trading.data.DukascopyDownloader;
import com.martinfou.trading.data.BarStore;

public final class HistoricalDataService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataService.class);

    private static final List<String> PAIRS = List.of(
        "eurusd", "gbpusd", "gbpjpy", "usdcad", "usdjpy", "audusd", "nzdusd", "usdchf"
    );

    public record DownloadTaskStatus(
        String key,
        int progress,
        String currentAction
    ) {}

    private final Path repoRoot;
    private final Path dukascopyDir;
    private final Path barsDir;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> activeDownloads = ConcurrentHashMap.newKeySet();
    private final Map<String, DownloadTaskStatus> activeTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HistoricalDataService() {
        this.repoRoot = EventStoreConfig.findRepoRoot() != null ? EventStoreConfig.findRepoRoot() : Path.of(".");
        this.dukascopyDir = repoRoot.resolve("data/historical/dukascopy");
        this.barsDir = repoRoot.resolve("data/historical/bars");
        ensureDirectories();
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(dukascopyDir);
            Files.createDirectories(barsDir);
        } catch (IOException e) {
            log.warn("Failed to create historical data directories", e);
        }
    }

    public record DatasetStatus(
        String symbol,
        String pair,
        int year,
        String timeframe,
        boolean csvExists,
        long csvSize,
        boolean barsExists,
        long barsSize
    ) {}

    public List<DatasetStatus> getStatus(String tf) {
        List<DatasetStatus> statusList = new ArrayList<>();
        int currentYear = LocalDate.now().getYear();

        for (int y = 2006; y <= currentYear; y++) {
            for (String pair : PAIRS) {
                String symbol = pairToSym(pair);
                Path csvFile = findCsvFile(pair, tf, y);
                Path barsFile = barsDir.resolve(symbol + "_" + tf.toUpperCase() + "_" + y + ".bars");

                boolean csvExists = csvFile != null && Files.isRegularFile(csvFile);
                long csvSize = csvExists ? getFileSize(csvFile) : 0;
                boolean barsExists = Files.isRegularFile(barsFile);
                long barsSize = barsExists ? getFileSize(barsFile) : 0;

                statusList.add(new DatasetStatus(
                    symbol, pair, y, tf, csvExists, csvSize, barsExists, barsSize
                ));
            }
        }
        return statusList;
    }

    private long getFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private Path findCsvFile(String pair, String tf, int year) {
        File[] files = dukascopyDir.toFile().listFiles();
        if (files == null) return null;
        String prefix = pair + "-" + tf.toLowerCase();
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(prefix) && name.contains(String.valueOf(year)) && name.endsWith(".csv")) {
                return f.toPath();
            }
        }
        return null;
    }

    public synchronized boolean triggerDownload(String pair, Integer year, String tf, boolean syncMode) {
        return triggerDownload(pair, year, year, tf, syncMode);
    }

    public synchronized boolean triggerDownload(String pair, Integer startYear, Integer endYear, String tf, boolean syncMode) {
        String key = syncMode ? "sync-" + tf : (startYear != null && endYear != null && !startYear.equals(endYear))
            ? pair + "-" + startYear + "-" + endYear + "-" + tf
            : pair + "-" + (startYear != null ? startYear : "") + "-" + tf;
        if (activeDownloads.contains(key)) {
            return false;
        }

        activeDownloads.add(key);
        activeTasks.put(key, new DownloadTaskStatus(key, 0, "Initializing..."));
        executor.submit(() -> {
            try {
                runDownloadProcess(pair, startYear, endYear, tf, syncMode);
            } finally {
                activeDownloads.remove(key);
                activeTasks.remove(key);
            }
        });
        return true;
    }

    private void runDownloadProcess(String pair, Integer startYear, Integer endYear, String tf, boolean syncMode) {
        String key = syncMode ? "sync-" + tf : (startYear != null && endYear != null && !startYear.equals(endYear))
            ? pair + "-" + startYear + "-" + endYear + "-" + tf
            : pair + "-" + (startYear != null ? startYear : "") + "-" + tf;

        DukascopyDownloader downloader = new DukascopyDownloader();
        int startYearVal = startYear != null ? startYear : 2006;
        int endYearVal = endYear != null ? endYear : LocalDate.now().getYear();

        List<String> pairsToDownload = pair != null ? List.of(pair) : PAIRS;

        if (syncMode) {
            log.info("Starting Java-native sync mode download from {} to {} for timeframe: {}", startYearVal, endYearVal, tf);
            int totalOperations = (endYearVal - startYearVal + 1) * PAIRS.size();
            int completedOperations = 0;

            for (int y = startYearVal; y <= endYearVal; y++) {
                for (String p : PAIRS) {
                    completedOperations++;
                    int pct = (completedOperations * 100) / totalOperations;
                    activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(pct, 1, 99), "Syncing " + pairToSym(p) + " " + y + "..."));

                    Path csv = findCsvFile(p, tf, y);
                    Path bars = barsDir.resolve(pairToSym(p) + "_" + tf.toUpperCase() + "_" + y + ".bars");

                    boolean shouldDownload = false;
                    if (csv == null || !Files.exists(csv)) {
                        shouldDownload = true;
                    } else if (y == LocalDate.now().getYear()) {
                        // Current year: refresh
                        deleteDataset(p, y, tf);
                        shouldDownload = true;
                    }

                    if (shouldDownload) {
                        try {
                            Path downloadedCsv = downloader.download(p, y, tf, dukascopyDir);
                            var store = new BarStore(pairToSym(p), tf.toUpperCase() + "_" + y, barsDir);
                            store.writeFromCSV(downloadedCsv);
                        } catch (Exception e) {
                            log.error("Failed to sync pair {} for year {}", p, y, e);
                        }
                    } else if (!Files.exists(bars) && csv != null) {
                        try {
                            var store = new BarStore(pairToSym(p), tf.toUpperCase() + "_" + y, barsDir);
                            store.writeFromCSV(csv);
                        } catch (Exception e) {
                            log.error("Failed to convert existing CSV to bars for pair {} year {}", p, y, e);
                        }
                    }
                }
            }
        } else {
            log.info("Starting Java-native download from {} to {} for pair: {} timeframe: {}", startYearVal, endYearVal, pair, tf);
            int totalYears = endYearVal - startYearVal + 1;
            int processedYears = 0;

            for (int y = startYearVal; y <= endYearVal; y++) {
                for (String p : pairsToDownload) {
                    int pct = (processedYears * 100) / totalYears + 5;
                    activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(pct, 1, 99), "Downloading " + pairToSym(p) + " " + y + "..."));
                    
                    try {
                        Path downloadedCsv = downloader.download(p, y, tf, dukascopyDir);
                        activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(pct + 5, 1, 99), "Converting " + pairToSym(p) + " " + y + " to binary..."));
                        var store = new BarStore(pairToSym(p), tf.toUpperCase() + "_" + y, barsDir);
                        store.writeFromCSV(downloadedCsv);
                    } catch (Exception e) {
                        log.error("Failed to download or convert pair {} for year {}", p, y, e);
                    }
                }
                processedYears++;
            }
        }
        log.info("Download process completed for key: {}", key);
    }

    public synchronized void deleteDataset(String pair, int year, String tf) {
        String symbol = pairToSym(pair);
        Path csvFile = findCsvFile(pair, tf, year);
        Path barsFile = barsDir.resolve(symbol + "_" + tf.toUpperCase() + "_" + year + ".bars");

        try {
            if (csvFile != null && Files.exists(csvFile)) {
                Files.delete(csvFile);
                log.info("Deleted CSV file: {}", csvFile);
            }
            if (Files.exists(barsFile)) {
                Files.delete(barsFile);
                log.info("Deleted bars file: {}", barsFile);
            }
        } catch (IOException e) {
            log.error("Failed to delete dataset for " + pair + " " + year + " " + tf, e);
        }
    }

    public void startWeeklyScheduler() {
        // Schedule every Sunday at 02:00 UTC
        long initialDelay = computeDelayToNextSunday2Am();
        scheduler.scheduleAtFixedRate(() -> {
            log.info("Triggering scheduled weekly historical data sync...");
            triggerDownload(null, null, "h1", true);
            // Wait slightly before M1 sync to avoid conflicts
            try {
                Thread.sleep(60000);
            } catch (InterruptedException ignored) {}
            triggerDownload(null, null, "m1", true);
        }, initialDelay, 7 * 24 * 60 * 60, TimeUnit.SECONDS);
        log.info("Scheduled weekly historical data sync scheduler initialized.");
    }

    private long computeDelayToNextSunday2Am() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(Clock.systemUTC().getZone());
        java.time.ZonedDateTime nextSunday = now.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
            .withHour(2).withMinute(0).withSecond(0).withNano(0);
        if (nextSunday.isBefore(now)) {
            nextSunday = nextSunday.plusWeeks(1);
        }
        return java.time.Duration.between(now, nextSunday).toSeconds();
    }

    public Set<String> getActiveDownloads() {
        return Collections.unmodifiableSet(activeDownloads);
    }

    public Map<String, DownloadTaskStatus> getActiveTasks() {
        return Collections.unmodifiableMap(activeTasks);
    }

    private static String pairToSym(String pair) {
        return switch (pair.toLowerCase()) {
            case "eurusd" -> "EUR_USD";
            case "gbpusd" -> "GBP_USD";
            case "usdcad" -> "USD_CAD";
            case "usdjpy" -> "USD_JPY";
            case "audusd" -> "AUD_USD";
            case "nzdusd" -> "NZD_USD";
            case "usdchf" -> "USD_CHF";
            case "gbpjpy" -> "GBP_JPY";
            default -> pair.toUpperCase();
        };
    }

    @Override
    public void close() {
        scheduler.shutdown();
        executor.shutdown();
    }
}
