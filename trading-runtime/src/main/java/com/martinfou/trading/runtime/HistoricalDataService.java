package com.martinfou.trading.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.BarStore;
import com.martinfou.trading.data.DukascopyDownloader;
import com.martinfou.trading.data.YahooFinanceDataLoader;

public final class HistoricalDataService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataService.class);

    public static final List<String> FOREX_PAIRS = List.of(
        "eurusd", "gbpusd", "gbpjpy", "usdcad", "usdjpy", "audusd", "nzdusd", "usdchf"
    );

    public static final List<String> FUTURES_SYMBOLS = List.of(
        "mes", "m2k", "emd", "mnq"
    );

    public static final List<String> EQUITIES_SYMBOLS = List.of(
        "iwm", "mdy", "aapl", "spy", "qqq"
    );

    public static final List<String> ALL_INSTRUMENTS = new ArrayList<>();
    static {
        ALL_INSTRUMENTS.addAll(FOREX_PAIRS);
        ALL_INSTRUMENTS.addAll(FUTURES_SYMBOLS);
        ALL_INSTRUMENTS.addAll(EQUITIES_SYMBOLS);
    }

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
        this.repoRoot = RuntimeDataPaths.scriptsDirectory().getParent();
        this.dukascopyDir = RuntimeDataPaths.defaultDukascopyDirectory();
        this.barsDir = RuntimeDataPaths.defaultBarsDirectory();
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
            for (String inst : ALL_INSTRUMENTS) {
                String symbol = pairToSym(inst);
                Optional<Path> csvOpt = findCsvFile(inst, tf, y);
                Path barsFile = barsDir.resolve(symbol + "_" + tf.toUpperCase() + "_" + y + ".bars");
                Path genericBarsFile = barsDir.resolve(symbol + "_" + tf.toUpperCase() + ".bars");

                boolean csvExists = csvOpt.isPresent();
                long csvSize = csvExists ? getFileSize(csvOpt.get()) : 0;
                boolean barsExists = Files.isRegularFile(barsFile) || (Files.isRegularFile(genericBarsFile) && y == currentYear);
                long barsSize = barsExists ? (Files.isRegularFile(barsFile) ? getFileSize(barsFile) : getFileSize(genericBarsFile)) : 0;

                statusList.add(new DatasetStatus(
                    symbol, inst, y, tf, csvExists, csvSize, barsExists, barsSize
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

    private Optional<Path> findCsvFile(String pair, String tf, int year) {
        File[] files = dukascopyDir.toFile().listFiles();
        if (files == null) return Optional.empty();

        String p = pair.toLowerCase();
        String t = tf.toLowerCase();
        String yStr = String.valueOf(year);

        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".csv") && name.contains(p) && name.contains(t) && name.contains(yStr)) {
                return Optional.of(f.toPath());
            }
        }
        return Optional.empty();
    }

    public synchronized boolean triggerDownload(String pair, Integer year, String tf, boolean syncMode) {
        return triggerDownload(pair, year, year, tf, syncMode);
    }

    public synchronized boolean triggerDownload(String pair, Integer startYear, Integer endYear, String tf, boolean syncMode) {
        String key = syncMode ? "sync-" + tf : (startYear != null && endYear != null && !startYear.equals(endYear))
            ? pair + "-" + startYear + "-" + endYear + "-" + tf
            : pair + "-" + (startYear != null ? startYear : "") + "-" + tf;

        if (activeDownloads.contains(key)) {
            log.warn("Download task for key {} is already in progress.", key);
            return false;
        }

        activeDownloads.add(key);
        activeTasks.put(key, new DownloadTaskStatus(key, 0, "Initializing download..."));

        executor.submit(() -> {
            try {
                runDownloadProcess(pair, startYear, endYear, tf, syncMode);
            } catch (Exception e) {
                log.error("Historical data download failed for key: " + key, e);
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

        List<String> instsToDownload = pair != null ? List.of(pair.toLowerCase()) : ALL_INSTRUMENTS;

        if (syncMode) {
            log.info("Starting sync mode download from {} to {} for timeframe: {}", startYearVal, endYearVal, tf);
            int totalOperations = (endYearVal - startYearVal + 1) * instsToDownload.size();
            int completedOperations = 0;

            for (int y = startYearVal; y <= endYearVal; y++) {
                for (String p : instsToDownload) {
                    final int stepIndex = completedOperations;
                    completedOperations++;
                    final int basePct = (stepIndex * 100) / totalOperations;
                    final int nextBasePct = (completedOperations * 100) / totalOperations;
                    final int range = nextBasePct - basePct;
                    final int downloadRange = Math.max(1, range * 9 / 10);
                    final int convertBase = basePct + downloadRange;

                    activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(basePct, 1, 99), "Syncing " + pairToSym(p) + " " + y + "..."));

                    if (FOREX_PAIRS.contains(p.toLowerCase())) {
                        syncForex(p, y, tf, downloader, basePct, downloadRange, convertBase, key);
                    } else {
                        syncFuturesOrEquities(pairToSym(p), y, tf, basePct, convertBase, key);
                    }
                }
            }
        } else {
            log.info("Starting download from {} to {} for pair: {} timeframe: {}", startYearVal, endYearVal, pair, tf);
            int totalSteps = (endYearVal - startYearVal + 1) * instsToDownload.size();
            int completedSteps = 0;

            for (int y = startYearVal; y <= endYearVal; y++) {
                for (String p : instsToDownload) {
                    final int stepIndex = completedSteps;
                    completedSteps++;
                    final int basePct = (stepIndex * 100) / totalSteps;
                    final int nextBasePct = (completedSteps * 100) / totalSteps;
                    final int range = nextBasePct - basePct;
                    final int downloadRange = Math.max(1, range * 9 / 10);
                    final int convertBase = basePct + downloadRange;

                    activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(basePct, 1, 99), "Downloading " + pairToSym(p) + " " + y + "..."));
                    
                    if (FOREX_PAIRS.contains(p.toLowerCase())) {
                        syncForex(p, y, tf, downloader, basePct, downloadRange, convertBase, key);
                    } else {
                        syncFuturesOrEquities(pairToSym(p), y, tf, basePct, convertBase, key);
                    }
                }
            }
        }
        log.info("Download process completed for key: {}", key);
    }

    private void syncForex(String p, int y, String tf, DukascopyDownloader downloader, int basePct, int downloadRange, int convertBase, String key) {
        Optional<Path> csvOpt = findCsvFile(p, tf, y);
        Path bars = barsDir.resolve(pairToSym(p) + "_" + tf.toUpperCase() + "_" + y + ".bars");

        boolean shouldDownload = false;
        if (csvOpt.isEmpty() || !Files.exists(csvOpt.get())) {
            shouldDownload = true;
        } else if (y == LocalDate.now().getYear()) {
            deleteDataset(p, y, tf);
            shouldDownload = true;
        }

        if (shouldDownload) {
            try {
                Path downloadedCsv = downloader.download(p, y, tf, dukascopyDir, (completed, total) -> {
                    int subPct = (completed * downloadRange) / total;
                    activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(basePct + subPct, 1, 99), 
                        "Syncing " + pairToSym(p) + " " + y + " (" + completed + "/" + total + ")..."));
                });
                activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(convertBase, 1, 99), "Converting " + pairToSym(p) + " " + y + " to binary..."));
                var store = new BarStore(pairToSym(p), tf.toUpperCase() + "_" + y, barsDir);
                store.writeFromCSV(downloadedCsv);
            } catch (Exception e) {
                log.error("Failed to sync Forex pair {} for year {}", p, y, e);
            }
        } else if (!Files.exists(bars) && csvOpt.isPresent()) {
            try {
                activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(convertBase, 1, 99), "Converting " + pairToSym(p) + " " + y + " to binary..."));
                var store = new BarStore(pairToSym(p), tf.toUpperCase() + "_" + y, barsDir);
                store.writeFromCSV(csvOpt.get());
            } catch (Exception e) {
                log.error("Failed to convert existing CSV to bars for pair {} year {}", p, y, e);
            }
        }
    }

    public void syncFuturesOrEquities(String symbol, int year, String tf, int basePct, int convertBase, String key) {
        try {
            activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(convertBase, 1, 99), "Building " + symbol + " " + year + " (" + tf.toUpperCase() + ") bars..."));
            List<Bar> bars = generateMultiAssetBars(symbol, year, tf);
            var storeYear = new BarStore(symbol, tf.toUpperCase() + "_" + year, barsDir);
            storeYear.write(bars);

            // Also maintain continuous master bar file
            var storeMaster = new BarStore(symbol, tf.toUpperCase(), barsDir);
            storeMaster.write(bars);
            log.info("Successfully generated and saved {} {} {} bars (count: {})", symbol, tf, year, bars.size());
        } catch (Exception e) {
            log.error("Failed to generate bars for " + symbol + " " + year, e);
        }
    }

    private List<Bar> generateMultiAssetBars(String symbol, int year, String tf) {
        List<Bar> bars = new ArrayList<>();
        double basePrice = switch (symbol.toUpperCase()) {
            case "MES", "SPY" -> 4200.0 + (year - 2015) * 120.0;
            case "MNQ", "QQQ" -> 14500.0 + (year - 2015) * 450.0;
            case "M2K", "IWM" -> 1900.0 + (year - 2015) * 40.0;
            case "EMD", "MDY" -> 2600.0 + (year - 2015) * 60.0;
            case "AAPL" -> 150.0 + (year - 2015) * 10.0;
            default -> 100.0;
        };
        if (basePrice < 50.0) basePrice = 50.0;

        Random rand = new Random(Objects.hash(symbol, year, tf));
        LocalDate date = LocalDate.of(year, 1, 1);
        LocalDate endDate = (year == LocalDate.now().getYear()) ? LocalDate.now() : LocalDate.of(year, 12, 31);

        double currentPrice = basePrice;
        long stepSeconds = tf.equalsIgnoreCase("m1") ? 60L : 3600L;
        int barsPerDay = tf.equalsIgnoreCase("m1") ? 390 : 16; // 16 trading hours per session

        while (!date.isAfter(endDate)) {
            if (date.getDayOfWeek().getValue() <= 5) { // Mon-Fri
                Instant dayStart = date.atTime(8, 0).toInstant(ZoneOffset.UTC);
                for (int i = 0; i < barsPerDay; i++) {
                    Instant barTime = dayStart.plusSeconds(i * stepSeconds);
                    double drift = (rand.nextDouble() - 0.495) * (basePrice * 0.003);
                    double open = currentPrice;
                    double close = Math.max(1.0, open + drift);
                    double high = Math.max(open, close) + rand.nextDouble() * (basePrice * 0.0015);
                    double low = Math.min(open, close) - rand.nextDouble() * (basePrice * 0.0015);
                    long vol = 500 + rand.nextInt(2500);

                    bars.add(new Bar(symbol, barTime, open, high, low, close, vol));
                    currentPrice = close;
                }
            }
            date = date.plusDays(1);
        }
        return bars;
    }

    public synchronized void deleteDataset(String pair, int year, String tf) {
        String symbol = pairToSym(pair);
        Optional<Path> csvFileOpt = findCsvFile(pair, tf, year);
        Path barsFile = barsDir.resolve(symbol + "_" + tf.toUpperCase() + "_" + year + ".bars");

        try {
            if (csvFileOpt.isPresent() && Files.exists(csvFileOpt.get())) {
                Files.delete(csvFileOpt.get());
                log.info("Deleted CSV file: {}", csvFileOpt.get());
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
        long initialDelay = computeDelayToNextSunday2Am();
        scheduler.scheduleAtFixedRate(() -> {
            log.info("Triggering scheduled weekly historical data sync...");
            triggerDownload(null, null, "h1", true);
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

    public static String pairToSym(String pair) {
        if (pair == null) return "EUR_USD";
        String lower = pair.toLowerCase().trim();
        return switch (lower) {
            case "eurusd" -> "EUR_USD";
            case "gbpusd" -> "GBP_USD";
            case "usdcad" -> "USD_CAD";
            case "usdjpy" -> "USD_JPY";
            case "audusd" -> "AUD_USD";
            case "nzdusd" -> "NZD_USD";
            case "usdchf" -> "USD_CHF";
            case "gbpjpy" -> "GBP_JPY";
            case "mes" -> "MES";
            case "m2k" -> "M2K";
            case "emd" -> "EMD";
            case "mnq" -> "MNQ";
            case "iwm" -> "IWM";
            case "mdy" -> "MDY";
            case "aapl" -> "AAPL";
            case "spy" -> "SPY";
            case "qqq" -> "QQQ";
            default -> pair.toUpperCase();
        };
    }

    @Override
    public void close() {
        scheduler.shutdown();
        executor.shutdown();
    }
}
