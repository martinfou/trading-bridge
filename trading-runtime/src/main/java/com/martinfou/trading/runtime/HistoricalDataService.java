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
        String key = syncMode ? "sync-" + tf : pair + "-" + year + "-" + tf;
        if (activeDownloads.contains(key)) {
            return false;
        }

        activeDownloads.add(key);
        activeTasks.put(key, new DownloadTaskStatus(key, 0, "Initializing..."));
        executor.submit(() -> {
            try {
                runDownloadProcess(pair, year, tf, syncMode);
            } finally {
                activeDownloads.remove(key);
                activeTasks.remove(key);
            }
        });
        return true;
    }

    private void runDownloadProcess(String pair, Integer year, String tf, boolean syncMode) {
        String key = syncMode ? "sync-" + tf : pair + "-" + year + "-" + tf;
        List<String> command = new ArrayList<>();
        command.add("./scripts/download-data.sh");
        if (syncMode) {
            command.add("--sync");
        } else {
            command.add("--pair");
            command.add(pair);
            command.add("--year");
            command.add(String.valueOf(year));
        }
        command.add("--tf");
        command.add(tf.toLowerCase());

        log.info("Starting historical data download command: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repoRoot.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                int startYear = 2006;
                int endYear = LocalDate.now().getYear();
                int currentMonth = 1;
                int totalMonths = 12;
                while ((line = reader.readLine()) != null) {
                    log.debug("[download-data.sh] {}", line);

                    if (syncMode) {
                        if (line.contains("—")) {
                            String[] parts = line.split("—");
                            if (parts.length > 1) {
                                try {
                                    String yearStr = parts[1].replaceAll("[^0-9]", "").trim();
                                    if (yearStr.length() == 4) {
                                        int y = Integer.parseInt(yearStr);
                                        int pct = (y - startYear) * 100 / (endYear - startYear + 1);
                                        activeTasks.put(key, new DownloadTaskStatus(key, Math.clamp(pct, 1, 99), "Syncing year " + y + "..."));
                                    }
                                } catch (Exception ignored) {}
                            }
                        }

                        String detectedPair = null;
                        for (String p : PAIRS) {
                            if (line.toLowerCase().contains(p)) {
                                detectedPair = p;
                                break;
                            }
                        }
                        if (detectedPair != null) {
                            var current = activeTasks.get(key);
                            int basePct = current != null ? current.progress() : 1;
                            activeTasks.put(key, new DownloadTaskStatus(key, basePct, "Syncing " + pairToSym(detectedPair) + "..."));
                        }
                    } else {
                        String detectedPair = null;
                        for (String p : PAIRS) {
                            if (line.toLowerCase().contains(p)) {
                                detectedPair = p;
                                break;
                            }
                        }

                        if (line.contains("Ingesting Month")) {
                            try {
                                String parts = line.substring(line.indexOf("Month") + 5).trim();
                                String[] slash = parts.split("/");
                                if (slash.length > 0) {
                                    currentMonth = Integer.parseInt(slash[0].trim());
                                    totalMonths = slash.length > 1 ? Integer.parseInt(slash[1].replaceAll("[^0-9]", "").trim()) : 12;
                                    
                                    int pct = 5 + (currentMonth * 80 / totalMonths);
                                    if (pair == null) {
                                        int pIdx = PAIRS.indexOf(detectedPair != null ? detectedPair : "");
                                        if (pIdx >= 0) {
                                            int base = pIdx * 100 / 8;
                                            pct = base + (currentMonth * 12 / totalMonths);
                                        }
                                    }
                                    activeTasks.put(key, new DownloadTaskStatus(key, pct, "Ingesting " + pairToSym(detectedPair != null ? detectedPair : pair) + " Month " + currentMonth + "/" + totalMonths + "..."));
                                }
                            } catch (Exception ignored) {}
                        } else {
                            if (pair == null) {
                                if (detectedPair != null) {
                                    int idx = PAIRS.indexOf(detectedPair);
                                    int base = idx * 100 / 8;
                                    if (line.contains("downloading...")) {
                                        activeTasks.put(key, new DownloadTaskStatus(key, base + 3, "Downloading " + pairToSym(detectedPair) + " (" + (idx + 1) + "/8)..."));
                                    } else if (line.contains("Converting file") || line.contains("Converted")) {
                                        activeTasks.put(key, new DownloadTaskStatus(key, base + 9, "Converting " + pairToSym(detectedPair) + " (" + (idx + 1) + "/8)..."));
                                    } else {
                                        activeTasks.put(key, new DownloadTaskStatus(key, base, "Syncing " + pairToSym(detectedPair) + " (" + (idx + 1) + "/8)..."));
                                    }
                                }
                            } else {
                                if (line.contains("downloading...")) {
                                    if (tf.equalsIgnoreCase("m1") || tf.equalsIgnoreCase("m1_data")) {
                                        int pct = 5 + (currentMonth * 80 / totalMonths);
                                        activeTasks.put(key, new DownloadTaskStatus(key, pct, "Downloading " + pairToSym(pair) + " Month " + currentMonth + "/" + totalMonths + "..."));
                                    } else {
                                        activeTasks.put(key, new DownloadTaskStatus(key, 30, "Downloading " + pairToSym(pair) + " " + year + "..."));
                                    }
                                } else if (line.contains("Converting file") || line.contains("Converted")) {
                                    if (tf.equalsIgnoreCase("m1") || tf.equalsIgnoreCase("m1_data")) {
                                        activeTasks.put(key, new DownloadTaskStatus(key, 90, "Converting " + pairToSym(pair) + " " + year + " to binary `.bars`..."));
                                    } else {
                                        activeTasks.put(key, new DownloadTaskStatus(key, 75, "Converting " + pairToSym(pair) + " " + year + " to binary `.bars`..."));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            int exitCode = process.waitFor();
            log.info("Download process completed with exit code: {}", exitCode);
        } catch (Exception e) {
            log.error("Failed to execute download-data.sh script", e);
        }
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
