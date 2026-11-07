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

import com.martinfou.trading.backtest.persistence.BacktestPersistenceService;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.InstrumentDefinition;
import com.martinfou.trading.data.BarStore;
import com.martinfou.trading.data.DukascopyDownloader;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.data.YahooFinanceDataLoader;
import com.martinfou.trading.data.ibkr.IbkrBarSource;
import com.martinfou.trading.data.persistence.SqliteUniverseStore;

public final class HistoricalDataService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataService.class);

    public record DownloadTaskStatus(
        String key,
        int progress,
        String currentAction,
        String symbol,
        String timeframe,
        int currentYear,
        int startYear,
        int endYear,
        int completedSteps,
        int totalSteps,
        String state
    ) {
        public DownloadTaskStatus(String key, int progress, String currentAction) {
            this(key, progress, currentAction, "", "", 0, 0, 0, 0, 1, "RUNNING");
        }
    }

    private static final Set<String> GLOBAL_ACTIVE_DOWNLOADS = ConcurrentHashMap.newKeySet();
    private static final Map<String, DownloadTaskStatus> GLOBAL_ACTIVE_TASKS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService GLOBAL_CLEANUP_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "historical-data-task-cleanup");
            t.setDaemon(true);
            return t;
        });

    private final Path repoRoot;
    private final Path dukascopyDir;
    private final Path barsDir;
    private final SqliteUniverseStore universeStore;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> activeDownloads = GLOBAL_ACTIVE_DOWNLOADS;
    private final Map<String, DownloadTaskStatus> activeTasks = GLOBAL_ACTIVE_TASKS;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public static void reportExternalTaskProgress(
        String key,
        int progress,
        String currentAction,
        String symbol,
        String timeframe,
        int currentYear,
        int startYear,
        int endYear,
        int completedSteps,
        int totalSteps
    ) {
        GLOBAL_ACTIVE_DOWNLOADS.add(key);
        GLOBAL_ACTIVE_TASKS.put(key, new DownloadTaskStatus(
            key,
            Math.clamp(progress, 0, 100),
            currentAction,
            symbol != null ? symbol.toUpperCase(Locale.ROOT) : "",
            timeframe != null ? timeframe.toUpperCase(Locale.ROOT) : "",
            currentYear,
            startYear,
            endYear,
            completedSteps,
            Math.max(1, totalSteps),
            "RUNNING"
        ));
    }

    public static void finishExternalTask(String key, String symbol, String timeframe, int startYear, int endYear, int totalSteps, String errorMessage) {
        GLOBAL_ACTIVE_DOWNLOADS.remove(key);
        boolean ok = (errorMessage == null || errorMessage.isBlank());
        String action = ok
            ? "Completed " + (symbol != null ? symbol.toUpperCase(Locale.ROOT) : key) + " (" + (timeframe != null ? timeframe.toUpperCase(Locale.ROOT) : "") + ")"
            : "Failed: " + errorMessage;
        GLOBAL_ACTIVE_TASKS.put(key, new DownloadTaskStatus(
            key,
            ok ? 100 : 0,
            action,
            symbol != null ? symbol.toUpperCase(Locale.ROOT) : "",
            timeframe != null ? timeframe.toUpperCase(Locale.ROOT) : "",
            endYear,
            startYear,
            endYear,
            ok ? Math.max(1, totalSteps) : 0,
            Math.max(1, totalSteps),
            ok ? "COMPLETED" : "FAILED"
        ));
        GLOBAL_CLEANUP_SCHEDULER.schedule(() -> GLOBAL_ACTIVE_TASKS.remove(key), ok ? 4 : 6, TimeUnit.SECONDS);
    }

    public HistoricalDataService() {
        this(new SqliteUniverseStore(BacktestPersistenceService.resolveDefaultDbPath()));
    }

    public HistoricalDataService(SqliteUniverseStore universeStore) {
        this.repoRoot = RuntimeDataPaths.scriptsDirectory().getParent();
        this.dukascopyDir = RuntimeDataPaths.defaultDukascopyDirectory();
        this.barsDir = RuntimeDataPaths.defaultBarsDirectory();
        this.universeStore = universeStore != null ? universeStore : new SqliteUniverseStore(BacktestPersistenceService.resolveDefaultDbPath());
        ensureDirectories();
    }

    public SqliteUniverseStore getUniverseStore() {
        return universeStore;
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
        List<InstrumentDefinition> allInstruments = universeStore.listAll();

        for (int y = 2006; y <= currentYear; y++) {
            for (InstrumentDefinition inst : allInstruments) {
                String symbol = inst.symbol();
                String rawPair = inst.symbol().toLowerCase().replace("_", "");
                Optional<Path> csvOpt = findCsvFile(rawPair, tf, y);
                Path barsFile = barsDir.resolve(symbol + "_" + tf.toUpperCase() + "_" + y + ".bars");
                Path genericBarsFile = barsDir.resolve(symbol + "_" + tf.toUpperCase() + ".bars");

                boolean csvExists = csvOpt.isPresent();
                long csvSize = csvExists ? getFileSize(csvOpt.get()) : 0;
                boolean barsExists = Files.isRegularFile(barsFile) || (Files.isRegularFile(genericBarsFile) && y == currentYear);
                long barsSize = barsExists ? (Files.isRegularFile(barsFile) ? getFileSize(barsFile) : getFileSize(genericBarsFile)) : 0;

                statusList.add(new DatasetStatus(
                    symbol, rawPair, y, tf, csvExists, csvSize, barsExists, barsSize
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
        String p = pair.toLowerCase().replace("_", "");
        String t = tf.toLowerCase();
        String yStr = String.valueOf(year);

        List<Path> csvDirs = List.of(dukascopyDir, repoRoot.resolve("data/historical/csv"));
        for (Path dir : csvDirs) {
            File[] files = dir.toFile().listFiles();
            if (files == null) continue;
            for (File f : files) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                boolean pairMatch = name.startsWith(p + "_") || name.startsWith(p + "-");
                boolean tfMatch = name.contains("_" + t + "_") || name.contains("-" + t + "-");
                boolean yearMatch = name.endsWith("_" + yStr + ".csv")
                    || name.endsWith("-" + yStr + ".csv")
                    || name.contains("-" + yStr + "-01-01");
                if (name.endsWith(".csv") && pairMatch && tfMatch && yearMatch) {
                    return Optional.of(f.toPath());
                }
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

        int startYearVal = startYear != null ? startYear : (syncMode ? LocalDate.now().getYear() : 2006);
        int endYearVal = endYear != null ? endYear : LocalDate.now().getYear();
        String symLabel = syncMode ? "ALL" : (pair != null ? pair.toUpperCase(Locale.ROOT) : "ALL");
        String tfLabel = tf != null ? tf.toUpperCase(Locale.ROOT) : "H1";

        activeDownloads.add(key);
        activeTasks.put(key, new DownloadTaskStatus(
            key, 2, "Initializing ingestion for " + symLabel + " (" + tfLabel + ")...",
            symLabel, tfLabel, startYearVal, startYearVal, endYearVal, 0, Math.max(1, endYearVal - startYearVal + 1), "RUNNING"
        ));

        executor.submit(() -> {
            String errorMsg = null;
            int totalSteps = Math.max(1, endYearVal - startYearVal + 1);
            try {
                totalSteps = runDownloadProcess(pair, startYearVal, endYearVal, tf, syncMode, key);
            } catch (Exception e) {
                log.error("Historical data download failed for key: " + key, e);
                errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } finally {
                finishExternalTask(key, symLabel, tfLabel, startYearVal, endYearVal, totalSteps, errorMsg);
            }
        });
        return true;
    }

    private int runDownloadProcess(String pair, int startYearVal, int endYearVal, String tf, boolean syncMode, String key) {
        DukascopyDownloader downloader = new DukascopyDownloader();
        String tfLabel = tf != null ? tf.toUpperCase(Locale.ROOT) : "H1";

        List<InstrumentDefinition> targetInstruments;
        if (pair != null && !pair.isBlank()) {
            Optional<InstrumentDefinition> defOpt = universeStore.getBySymbol(pair);
            targetInstruments = defOpt.map(List::of).orElseGet(() -> List.of(
                InstrumentDefinition.custom(pair.toUpperCase(), pair.toUpperCase(), "EQUITIES", 1.0, 0.01)
            ));
        } else {
            targetInstruments = universeStore.listAll();
        }

        int totalSteps = Math.max(1, (endYearVal - startYearVal + 1) * targetInstruments.size());
        int completedSteps = 0;

        log.info("Starting {} download from {} to {} for pair: {} timeframe: {}",
            syncMode ? "sync mode" : "dataset", startYearVal, endYearVal, pair, tf);

        for (int y = startYearVal; y <= endYearVal; y++) {
            for (InstrumentDefinition inst : targetInstruments) {
                final int stepIndex = completedSteps;
                completedSteps++;
                final int basePct = (stepIndex * 100) / totalSteps;
                final int nextBasePct = (completedSteps * 100) / totalSteps;
                final int range = Math.max(1, nextBasePct - basePct);
                final int downloadRange = Math.max(1, range * 85 / 100);
                final int convertBase = basePct + downloadRange;
                final int stepNum = completedSteps;

                String actionPrefix = syncMode ? "Syncing " : "Downloading ";
                activeTasks.put(key, new DownloadTaskStatus(
                    key,
                    Math.clamp(Math.max(basePct, 3), 1, 99),
                    actionPrefix + inst.symbol() + " " + y + " (" + tfLabel + ") — step " + stepNum + "/" + totalSteps + "...",
                    inst.symbol(),
                    tfLabel,
                    y,
                    startYearVal,
                    endYearVal,
                    stepIndex,
                    totalSteps,
                    "RUNNING"
                ));

                if ("FOREX".equalsIgnoreCase(inst.assetClass())) {
                    syncForex(inst.symbol(), y, tf, downloader, basePct, downloadRange, convertBase, key, startYearVal, endYearVal, stepIndex, totalSteps);
                } else {
                    syncFuturesOrEquities(inst.symbol(), y, tf, basePct, downloadRange, convertBase, key, startYearVal, endYearVal, stepIndex, totalSteps);
                }
            }
        }
        log.info("Download process completed for key: {}", key);
        return totalSteps;
    }

    private void syncForex(
        String symbol,
        int y,
        String tf,
        DukascopyDownloader downloader,
        int basePct,
        int downloadRange,
        int convertBase,
        String key,
        int startYearVal,
        int endYearVal,
        int stepIndex,
        int totalSteps
    ) {
        String p = symbol.toLowerCase().replace("_", "");
        String tfLabel = tf != null ? tf.toUpperCase(Locale.ROOT) : "H1";
        Optional<Path> csvOpt = findCsvFile(p, tf, y);
        Path bars = barsDir.resolve(symbol + "_" + tfLabel + "_" + y + ".bars");

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
                    int subPct = (completed * downloadRange) / Math.max(1, total);
                    int pct = Math.clamp(basePct + subPct, 1, 99);
                    activeTasks.put(key, new DownloadTaskStatus(
                        key,
                        pct,
                        "Downloading " + symbol + " " + y + " (" + completed + "/" + total + " days)...",
                        symbol,
                        tfLabel,
                        y,
                        startYearVal,
                        endYearVal,
                        stepIndex,
                        totalSteps,
                        "RUNNING"
                    ));
                });
                activeTasks.put(key, new DownloadTaskStatus(
                    key,
                    Math.clamp(convertBase, 1, 99),
                    "Converting " + symbol + " " + y + " (" + tfLabel + ") to binary store...",
                    symbol,
                    tfLabel,
                    y,
                    startYearVal,
                    endYearVal,
                    stepIndex,
                    totalSteps,
                    "RUNNING"
                ));
                var store = new BarStore(symbol, tfLabel + "_" + y, barsDir);
                store.writeFromCSV(downloadedCsv);
            } catch (Exception e) {
                log.error("Failed to sync Forex pair {} for year {}", p, y, e);
            }
        } else if (!Files.exists(bars) && csvOpt.isPresent()) {
            try {
                activeTasks.put(key, new DownloadTaskStatus(
                    key,
                    Math.clamp(convertBase, 1, 99),
                    "Converting cached CSV for " + symbol + " " + y + " (" + tfLabel + ")...",
                    symbol,
                    tfLabel,
                    y,
                    startYearVal,
                    endYearVal,
                    stepIndex,
                    totalSteps,
                    "RUNNING"
                ));
                var store = new BarStore(symbol, tfLabel + "_" + y, barsDir);
                store.writeFromCSV(csvOpt.get());
            } catch (Exception e) {
                log.error("Failed to convert existing CSV to bars for pair {} year {}", p, y, e);
            }
        }
    }

    public void syncFuturesOrEquities(String symbol, int year, String tf, int basePct, int convertBase, String key) {
        syncFuturesOrEquities(symbol, year, tf, basePct, Math.max(1, convertBase - basePct), convertBase, key, year, year, 0, 1);
    }

    public void syncFuturesOrEquities(
        String symbol,
        int year,
        String tf,
        int basePct,
        int downloadRange,
        int convertBase,
        String key,
        int startYearVal,
        int endYearVal,
        int stepIndex,
        int totalSteps
    ) {
        String tfLabel = tf != null ? tf.toUpperCase(Locale.ROOT) : "H1";
        try {
            activeTasks.put(key, new DownloadTaskStatus(
                key,
                Math.clamp(basePct + Math.max(1, downloadRange / 4), 1, 99),
                "Fetching " + symbol + " " + year + " (" + tfLabel + ") market bars...",
                symbol,
                tfLabel,
                year,
                startYearVal,
                endYearVal,
                stepIndex,
                totalSteps,
                "RUNNING"
            ));
            List<Bar> bars = fetchOrGenerateMultiAssetBars(symbol, year, tfLabel, dukascopyDir, (completed, total) -> {
                int subPct = (completed * downloadRange) / Math.max(1, total);
                int pct = Math.clamp(basePct + subPct, 1, 99);
                activeTasks.put(key, new DownloadTaskStatus(
                    key,
                    pct,
                    "Downloading " + symbol + " " + year + " (" + completed + "/" + total + " days)...",
                    symbol,
                    tfLabel,
                    year,
                    startYearVal,
                    endYearVal,
                    stepIndex,
                    totalSteps,
                    "RUNNING"
                ));
            });
            activeTasks.put(key, new DownloadTaskStatus(
                key,
                Math.clamp(convertBase, 1, 99),
                "Writing " + bars.size() + " bars for " + symbol + " " + year + " (" + tfLabel + ")...",
                symbol,
                tfLabel,
                year,
                startYearVal,
                endYearVal,
                stepIndex,
                totalSteps,
                "RUNNING"
            ));
            var storeYear = new BarStore(symbol, tfLabel + "_" + year, barsDir);
            storeYear.write(bars);

            // Maintain continuous master bar file across all available annual datasets
            rebuildMasterBarStore(symbol, tfLabel);
            log.info("Successfully fetched and saved {} {} {} bars (count: {})", symbol, tf, year, bars.size());
        } catch (Exception e) {
            log.error("Failed to fetch/save bars for " + symbol + " " + year, e);
        }
    }

    private void rebuildMasterBarStore(String symbol, String tfLabel) {
        try {
            List<Bar> allBars = HistoricalDataLoader.loadAllAvailable(symbol, tfLabel, barsDir, dukascopyDir).bars();
            if (!allBars.isEmpty()) {
                var storeMaster = new BarStore(symbol, tfLabel, barsDir);
                storeMaster.write(allBars);
            }
        } catch (Exception e) {
            log.debug("Could not rebuild master .bars store for {} {}: {}", symbol, tfLabel, e.getMessage());
        }
    }

    /**
     * Fetches real market bars for Futures / Commodities / Equities using a three-tier real-data cascade:
     * <ol>
     *   <li><b>Recent history via IBKR TWS / Gateway ({@link IbkrBarSource#tryFetchYearBars}):</b> Primary authoritative
     *       source when IB Gateway / TWS is running locally (ports 4002/7497) and the requested year is within IBKR's
     *       1-year contract lookback.</li>
     *   <li><b>Recent history via Yahoo Finance v8 Chart API ({@link YahooFinanceDataLoader#fetchYearBars}):</b> Used
     *       when IBKR is offline or for recent intraday windows (30d M1, 60d M30, 729d H1, full D1/W1).</li>
     *   <li><b>Deep historical intraday not available in IBKR ({@link DukascopyDownloader}):</b> Dukascopy CFD/Spot proxy
     *       for supported futures/metals ({@code MGC-&gt;XAUUSD}, {@code MES-&gt;USA500IDXUSD}, {@code MNQ-&gt;USATECHIDXUSD},
     *       {@code M2K-&gt;USA2000IDXUSD}) covering historical years or earlier months of the year prior to the IBKR/Yahoo tail.</li>
     *   <li><b>Offline fallback:</b> Deterministic {@link #generateMultiAssetBars} if running in unit-test mode
     *       ({@code -Dtrading.bridge.test=true}) or if all remote feeds are unreachable.</li>
     * </ol>
     */
    public static List<Bar> fetchOrGenerateMultiAssetBars(
        String symbol,
        int year,
        String tf,
        Path dukascopyOutputDir,
        DukascopyDownloader.ProgressListener progressListener
    ) {
        String tfLabel = (tf == null || tf.isBlank()) ? "H1" : tf.trim().toUpperCase(Locale.ROOT);
        if (Boolean.getBoolean("trading.bridge.test")) {
            return generateMultiAssetBars(symbol, year, tfLabel);
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Bar> recentBars = List.of();

        // 1. Primary recent history from IBKR TWS / Gateway (when Gateway socket is reachable)
        if (year >= today.getYear() - 1) {
            recentBars = IbkrBarSource.tryFetchYearBars(symbol, year, tfLabel);
            if (!recentBars.isEmpty()) {
                log.info("Fetched {} recent {} bars from IBKR Gateway for {} ({})", recentBars.size(), tfLabel, symbol, year);
            }
        }

        // 2. Secondary recent history from Yahoo Finance (when IBKR is offline or returned empty)
        if (recentBars.isEmpty() && YahooFinanceDataLoader.canServeYear(tfLabel, year, today)) {
            try {
                recentBars = YahooFinanceDataLoader.fetchYearBars(symbol, year, tfLabel);
                log.info("Fetched {} recent {} bars from Yahoo Finance for {} ({})", recentBars.size(), tfLabel, symbol, year);
            } catch (Exception e) {
                log.warn("Yahoo Finance fetch for {} {} ({}) failed: {}", symbol, year, tfLabel, e.getMessage());
            }
        }

        // Check if recentBars already covers from the start of the requested year (within first 5 calendar days)
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        if (!recentBars.isEmpty()) {
            LocalDate firstRecentDate = recentBars.getFirst().timestamp().atZone(ZoneOffset.UTC).toLocalDate();
            if (!firstRecentDate.isAfter(yearStart.plusDays(5))) {
                return recentBars;
            }
        }

        // 3. History not available in IBKR / Yahoo (older years or earlier months of current year) via Dukascopy proxy
        if (DukascopyDownloader.supportsSymbol(symbol)) {
            try {
                DukascopyDownloader downloader = new DukascopyDownloader();
                LocalDate yearEnd = LocalDate.of(year, 12, 31);
                if (yearEnd.isAfter(today)) {
                    yearEnd = today;
                }
                if (!recentBars.isEmpty()) {
                    LocalDate firstRecentDate = recentBars.getFirst().timestamp().atZone(ZoneOffset.UTC).toLocalDate();
                    if (!firstRecentDate.isBefore(yearStart)) {
                        // Include firstRecentDate so we have an overlapping bar timestamp to measure futures-vs-spot basis
                        yearEnd = firstRecentDate;
                    }
                }
                Path outDir = dukascopyOutputDir != null ? dukascopyOutputDir : RuntimeDataPaths.defaultDukascopyDirectory();
                List<Bar> dukascopyBars = List.of();
                if (!yearStart.isAfter(yearEnd)) {
                    Path csvPath = downloader.downloadRange(symbol, yearStart, yearEnd, tfLabel, outDir, progressListener);
                    dukascopyBars = HistoricalDataLoader.loadPath(csvPath, symbol);
                    if (!recentBars.isEmpty()) {
                        // Delete partial-year Dukascopy CSV so HistoricalDataLoader.loadYear does not prefer
                        // the un-stitched partial CSV over the stitched <SYMBOL>_<TF>_<YEAR>.bars file.
                        Files.deleteIfExists(csvPath);
                    }
                    log.info("Fetched {} historical {} bars from Dukascopy proxy ({}) for {} ({}..{})",
                        dukascopyBars.size(), tfLabel, DukascopyDownloader.toDukascopyInstrument(symbol), symbol, yearStart, yearEnd);
                }
                if (!dukascopyBars.isEmpty() || !recentBars.isEmpty()) {
                    return stitchDukascopyAndRecentBars(dukascopyBars, recentBars, symbol);
                }
            } catch (Exception e) {
                log.warn("Dukascopy proxy download for {} {} ({}) failed: {}", symbol, year, tfLabel, e.getMessage());
            }
        }

        if (!recentBars.isEmpty()) {
            return recentBars;
        }

        log.warn("Falling back to synthetic multi-asset bars for {} {} ({})", symbol, year, tfLabel);
        return generateMultiAssetBars(symbol, year, tfLabel);
    }

    /**
     * Stitches historical Dukascopy proxy bars with recent IBKR/Yahoo futures bars.
     * When both lists are non-empty, measures the futures-vs-spot basis at the first overlapping timestamp
     * (or boundary bar) and shifts the historical Dukascopy segment by that quantized basis so there is no
     * artificial carry gap at the stitch boundary. Recent IBKR/Yahoo bars take precedence from their start timestamp.
     */
    static List<Bar> stitchDukascopyAndRecentBars(List<Bar> dukascopyBars, List<Bar> recentBars, String symbol) {
        if (recentBars == null || recentBars.isEmpty()) {
            return BarStore.sanitizeChronological(dukascopyBars);
        }
        if (dukascopyBars == null || dukascopyBars.isEmpty()) {
            return BarStore.sanitizeChronological(recentBars);
        }

        Bar firstRecent = recentBars.getFirst();
        Instant cutoff = firstRecent.timestamp();

        // Find matching timestamp in dukascopyBars (or last bar before cutoff) to compute futures basis delta
        Bar anchorDuk = null;
        for (Bar b : dukascopyBars) {
            if (!b.timestamp().isAfter(cutoff)) {
                anchorDuk = b;
            } else {
                break;
            }
        }

        double basisDelta = 0.0;
        var contractOpt = com.martinfou.trading.core.FuturesRegistry.find(symbol);
        if (anchorDuk != null) {
            double anchorPrice = anchorDuk.timestamp().equals(cutoff) ? anchorDuk.open() : anchorDuk.close();
            double rawDelta = firstRecent.open() - anchorPrice;
            basisDelta = contractOpt.map(c -> c.quantizePrice(rawDelta)).orElse(rawDelta);
        }

        List<Bar> combined = new ArrayList<>(dukascopyBars.size() + recentBars.size());
        for (Bar b : dukascopyBars) {
            if (b.timestamp().isBefore(cutoff)) {
                if (basisDelta != 0.0) {
                    double o = Math.max(1e-6, b.open() + basisDelta);
                    double cl = Math.max(1e-6, b.close() + basisDelta);
                    double h = Math.max(1e-6, b.high() + basisDelta);
                    double l = Math.max(1e-6, b.low() + basisDelta);
                    if (contractOpt.isPresent()) {
                        var c = contractOpt.get();
                        o = c.quantizePrice(o);
                        cl = c.quantizePrice(cl);
                        h = Math.max(c.quantizePrice(h), Math.max(o, cl));
                        l = Math.min(c.quantizePrice(l), Math.min(o, cl));
                    } else {
                        h = Math.max(h, Math.max(o, cl));
                        l = Math.min(l, Math.min(o, cl));
                    }
                    combined.add(new Bar(b.symbol(), b.timestamp(), o, h, l, cl, b.volume()));
                } else {
                    combined.add(b);
                }
            }
        }
        combined.addAll(recentBars);
        return BarStore.sanitizeChronological(combined);
    }

    public static List<Bar> generateMultiAssetBars(String symbol, int year, String tf) {
        List<Bar> bars = new ArrayList<>();
        String normSym = symbol == null ? "MGC" : symbol.trim().toUpperCase(Locale.ROOT).replace('/', '_');
        double basePrice = switch (normSym) {
            case "MES", "ES", "SPY" -> 4200.0 + (year - 2015) * 120.0;
            case "MNQ", "NQ", "QQQ" -> 14500.0 + (year - 2015) * 450.0;
            case "M2K", "RTY", "IWM", "IJR", "VB", "SCHA" -> 190.0 + (year - 2015) * 6.0;
            case "EMD", "MDY" -> 2600.0 + (year - 2015) * 60.0;
            case "MGC", "GC", "XAU_USD" -> 2000.0 + (year - 2015) * 80.0;
            case "AAPL" -> 150.0 + (year - 2015) * 10.0;
            case "NVDA", "TSLA", "PLTR", "SOXL", "MSFT", "AMZN", "META", "GOOGL", "AMD", "NFLX", "JPM" -> 120.0 + (year - 2015) * 15.0;
            case "EUR_USD" -> 1.0850;
            case "GBP_USD" -> 1.2750;
            case "USD_JPY" -> 148.0;
            case "GBP_JPY" -> 188.0;
            case "EUR_JPY" -> 162.0;
            case "AUD_USD" -> 0.6600;
            case "NZD_USD" -> 0.6050;
            case "USD_CAD" -> 1.3600;
            case "USD_CHF" -> 0.8850;
            default -> 100.0 + (year - 2015) * 5.0;
        };
        boolean isQuoteUnderFive = switch (normSym) {
            case "EUR_USD", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CAD", "USD_CHF", "EUR_GBP" -> true;
            default -> false;
        };
        double minFloor = isQuoteUnderFive ? Math.max(0.10, basePrice * 0.4) : 10.0;
        if (basePrice < minFloor) basePrice = minFloor;

        Random rand = new Random(Objects.hash(symbol, year, tf));
        LocalDate date = LocalDate.of(year, 1, 1);
        LocalDate endDate = (year == LocalDate.now().getYear()) ? LocalDate.now() : LocalDate.of(year, 12, 31);

        double currentPrice = basePrice;
        String tfLower = tf == null ? "h1" : tf.toLowerCase(Locale.ROOT);
        long stepSeconds = switch (tfLower) {
            case "m1" -> 60L;
            case "m5" -> 300L;
            case "m15" -> 900L;
            case "m30" -> 1800L;
            case "h4" -> 14400L;
            case "d1" -> 86400L;
            case "w1" -> 604800L;
            default -> 3600L;
        };
        int barsPerDay = switch (tfLower) {
            case "m1" -> 390;
            case "m5" -> 78;
            case "m15" -> 26;
            case "m30" -> 13;
            case "h4" -> 4;
            case "d1", "w1" -> 1;
            default -> 16;
        };

        while (!date.isAfter(endDate)) {
            int dow = date.getDayOfWeek().getValue();
            boolean shouldEmitDay = "w1".equals(tfLower) ? (dow == 1) : (dow <= 5);
            if (shouldEmitDay) {
                Instant dayStart = date.atTime(8, 0).toInstant(ZoneOffset.UTC);
                for (int i = 0; i < barsPerDay; i++) {
                    Instant barTime = dayStart.plusSeconds(i * stepSeconds);
                    double drift = (rand.nextDouble() - 0.495) * (basePrice * 0.0025);
                    double open = currentPrice;
                    double close = Math.max(minFloor, open + drift);
                    double high = Math.max(open, close) + rand.nextDouble() * (basePrice * 0.0012);
                    double low = Math.max(minFloor * 0.5, Math.min(open, close) - rand.nextDouble() * (basePrice * 0.0012));
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
        String symbol = pair.toUpperCase().replace("/", "_");
        String rawPair = pair.toLowerCase().replace("_", "");
        Optional<Path> csvFileOpt = findCsvFile(rawPair, tf, year);
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

    @Override
    public void close() {
        scheduler.shutdown();
        executor.shutdown();
        universeStore.close();
    }
}
