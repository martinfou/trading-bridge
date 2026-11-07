package com.martinfou.trading.runtime.generator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.runtime.BarSourceResolver;
import com.martinfou.trading.runtime.HistoricalDataService;
import com.martinfou.trading.runtime.RuntimeDataPaths;
import com.martinfou.trading.runtime.generator.StrategyGenome.BlockType;
import com.martinfou.trading.runtime.generator.StrategyGenome.RuleGene;
import com.martinfou.trading.strategies.StrategyCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native StrategyQuant X-style Evolutionary Strategy Generator & Databank Manager.
 */
public final class StrategyGeneratorService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StrategyGeneratorService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record PartitionBand(
        int startIndex,
        int endIndex,
        boolean oos,
        String label
    ) {}

    public record GeneratorRequest(
        String symbol,
        String timeframe,
        String yearSpec,
        Integer oosSplitPct,
        String searchMode,
        String archetype,
        String direction,
        String entryOrderType,
        Integer populationSize,
        Integer generations,
        List<String> buildingBlocks,
        Integer minTrades,
        Double minProfitFactor,
        Double minOosProfitFactor,
        Double minSharpe,
        Double maxDrawdownPct,
        Double initialCapital,
        Double lotSize,
        Double commissionPerTrade,
        Double slippagePct,
        Integer splitFolds,
        String sessionMode,
        Integer entryStartHourEt,
        Integer entryEndHourEt,
        Integer allowedDaysMask,
        String exitSessionMode,
        Integer flatCutoffHourEt
    ) {
        public GeneratorRequest(
            String symbol,
            String timeframe,
            String yearSpec,
            Integer oosSplitPct,
            String searchMode,
            String archetype,
            String direction,
            String entryOrderType,
            Integer populationSize,
            Integer generations,
            List<String> buildingBlocks,
            Integer minTrades,
            Double minProfitFactor,
            Double minOosProfitFactor,
            Double minSharpe,
            Double maxDrawdownPct,
            Double initialCapital,
            Double lotSize,
            Double commissionPerTrade,
            Double slippagePct,
            Integer splitFolds
        ) {
            this(
                symbol, timeframe, yearSpec, oosSplitPct, searchMode, archetype, direction, entryOrderType,
                populationSize, generations, buildingBlocks, minTrades, minProfitFactor, minOosProfitFactor,
                minSharpe, maxDrawdownPct, initialCapital, lotSize, commissionPerTrade, slippagePct, splitFolds,
                "CONTINUOUS", 0, 24, 31, "CONTINUOUS", 16
            );
        }
    }

    public record DatabankCandidate(
        String id,
        String symbol,
        String timeframe,
        String yearSpec,
        String archetype,
        String direction,
        String entryOrderType,
        String tier,
        double compositeScore,
        int totalTrades,
        int isTrades,
        int oosTrades,
        double netProfit,
        double isNetProfit,
        double oosNetProfit,
        double totalReturnPct,
        double profitFactor,
        double isProfitFactor,
        double oosProfitFactor,
        double sharpeRatio,
        double isSharpeRatio,
        double oosSharpeRatio,
        double winRatePct,
        double maxDrawdownPct,
        double retDdRatio,
        double rSquared,
        double mcVar95,
        double mcLossProbPct,
        int generationFound,
        Instant createdAt,
        boolean promoted,
        String javaFilePath,
        String longSignalSummary,
        String shortSignalSummary,
        String entrySummary,
        String exitSummary,
        List<String> indicators,
        List<Double> equityCurve,
        int oosStartIndex,
        StrategyGenome genome,
        String javaSourceCode,
        Double initialCapital,
        Double lotSize,
        Double commissionPerTrade,
        Double slippagePct,
        Integer valStartIndex,
        Integer valTrades,
        Double valNetProfit,
        Double valProfitFactor,
        Double wfePct,
        Integer splitFolds,
        List<PartitionBand> partitionBands,
        String sessionSummary,
        Integer entryStartHourEt,
        Integer entryEndHourEt,
        Integer allowedDaysMask,
        String exitSessionMode,
        Integer flatCutoffHourEt
    ) {
        public String resolvedSessionSummary() {
            if (sessionSummary != null && !sessionSummary.isBlank()) {
                return sessionSummary;
            }
            if (genome != null) {
                return genome.sessionSummary();
            }
            return "Mon–Fri | 24h Session | Overnight Hold Allowed";
        }

        public Integer resolvedEntryStartHourEt() {
            return entryStartHourEt != null ? entryStartHourEt : (genome != null ? genome.resolvedEntryStartHourEt() : 0);
        }

        public Integer resolvedEntryEndHourEt() {
            return entryEndHourEt != null ? entryEndHourEt : (genome != null ? genome.resolvedEntryEndHourEt() : 24);
        }

        public Integer resolvedAllowedDaysMask() {
            return allowedDaysMask != null ? allowedDaysMask : (genome != null ? genome.resolvedAllowedDaysMask() : 31);
        }

        public String resolvedExitSessionMode() {
            return exitSessionMode != null ? exitSessionMode : (genome != null ? genome.resolvedExitSessionMode() : "CONTINUOUS");
        }

        public Integer resolvedFlatCutoffHourEt() {
            return flatCutoffHourEt != null ? flatCutoffHourEt : (genome != null ? genome.resolvedFlatCutoffHourEt() : 16);
        }
        public double resolvedCapital() {
            return initialCapital != null && initialCapital > 0
                ? initialCapital
                : LotSizing.defaultsForSymbol(symbol).capital();
        }

        public double resolvedLotSize() {
            return lotSize != null && lotSize > 0
                ? lotSize
                : LotSizing.defaultsForSymbol(symbol).lotSize();
        }

        public double resolvedCommission() {
            return commissionPerTrade != null && commissionPerTrade >= 0
                ? commissionPerTrade
                : LotSizing.defaultsForSymbol(symbol).commissionPerTrade();
        }

        public double resolvedSlippage() {
            return slippagePct != null && slippagePct >= 0
                ? slippagePct
                : LotSizing.defaultsForSymbol(symbol).slippagePct();
        }

        public int resolvedValStartIndex() {
            if (valStartIndex != null && valStartIndex > 0 && valStartIndex < oosStartIndex) {
                return valStartIndex;
            }
            return Math.max(1, (int) Math.round(oosStartIndex * (50.0 / 70.0)));
        }

        public int resolvedValTrades() {
            return valTrades != null ? valTrades : Math.max(1, (int) Math.round(isTrades * 0.28));
        }

        public double resolvedValNetProfit() {
            return valNetProfit != null ? valNetProfit : round2(isNetProfit * 0.28);
        }

        public double resolvedValProfitFactor() {
            return valProfitFactor != null && valProfitFactor > 0 ? valProfitFactor : isProfitFactor;
        }

        public double resolvedWfePct() {
            if (wfePct != null && wfePct > 0) {
                return wfePct;
            }
            if (isProfitFactor <= 0) {
                return 0.0;
            }
            return round2(Math.clamp((oosProfitFactor / isProfitFactor) * 100.0, 0.0, 200.0));
        }

        public int resolvedSplitFolds() {
            return splitFolds != null && splitFolds >= 1 ? splitFolds : 3;
        }

        public List<PartitionBand> resolvedPartitionBands() {
            if (partitionBands != null && !partitionBands.isEmpty()) {
                return partitionBands;
            }
            int len = equityCurve != null ? equityCurve.size() : 0;
            if (len < 6) {
                return List.of();
            }
            int oosPct = oosStartIndex > 0 && oosStartIndex < len - 1
                ? Math.clamp(100 - (int) Math.round((oosStartIndex / (double) (len - 1)) * 100.0), 15, 50)
                : 30;
            return buildSampledBands(len, resolvedSplitFolds(), oosPct);
        }

        public DatabankCandidate withoutJavaSourceCode() {
            if (javaSourceCode == null) {
                return this;
            }
            return new DatabankCandidate(
                id, symbol, timeframe, yearSpec, archetype, direction, entryOrderType,
                tier, compositeScore, totalTrades, isTrades, oosTrades,
                netProfit, isNetProfit, oosNetProfit, totalReturnPct,
                profitFactor, isProfitFactor, oosProfitFactor,
                sharpeRatio, isSharpeRatio, oosSharpeRatio,
                winRatePct, maxDrawdownPct, retDdRatio, rSquared,
                mcVar95, mcLossProbPct, generationFound, createdAt,
                promoted, javaFilePath, longSignalSummary, shortSignalSummary,
                entrySummary, exitSummary, indicators, equityCurve,
                oosStartIndex, genome, null,
                initialCapital, lotSize, commissionPerTrade, slippagePct,
                valStartIndex, valTrades, valNetProfit, valProfitFactor, wfePct,
                splitFolds, partitionBands,
                sessionSummary, entryStartHourEt, entryEndHourEt,
                allowedDaysMask, exitSessionMode, flatCutoffHourEt
            );
        }

        public DatabankCandidate withSynthesizedJavaSourceCode() {
            if (javaSourceCode != null && !javaSourceCode.isBlank()) {
                return this;
            }
            if (genome == null) {
                return this;
            }
            String synthesized = genome.toJavaSourceCode(
                netProfit, profitFactor, oosProfitFactor, sharpeRatio, maxDrawdownPct, totalTrades, compositeScore
            );
            return new DatabankCandidate(
                id, symbol, timeframe, yearSpec, archetype, direction, entryOrderType,
                tier, compositeScore, totalTrades, isTrades, oosTrades,
                netProfit, isNetProfit, oosNetProfit, totalReturnPct,
                profitFactor, isProfitFactor, oosProfitFactor,
                sharpeRatio, isSharpeRatio, oosSharpeRatio,
                winRatePct, maxDrawdownPct, retDdRatio, rSquared,
                mcVar95, mcLossProbPct, generationFound, createdAt,
                promoted, javaFilePath, longSignalSummary, shortSignalSummary,
                entrySummary, exitSummary, indicators, equityCurve,
                oosStartIndex, genome, synthesized,
                initialCapital, lotSize, commissionPerTrade, slippagePct,
                valStartIndex, valTrades, valNetProfit, valProfitFactor, wfePct,
                splitFolds, partitionBands,
                sessionSummary, entryStartHourEt, entryEndHourEt,
                allowedDaysMask, exitSessionMode, flatCutoffHourEt
            );
        }
    }

    private static volatile Path lastLoadedDbPath = null;
    private static volatile long lastLoadedDbMtime = -1L;
    private static volatile String lastLoadedFirstId = null;

    private final Path storageDir;
    private final SqliteStrategyDatabank sqliteDatabank;
    private final Path legacyDatabankFile;
    private final Path legacyPromotedFile;
    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "strategy-generator-job");
        t.setDaemon(true);
        return t;
    });

    private final CopyOnWriteArrayList<DatabankCandidate> databank = new CopyOnWriteArrayList<>();
    private final Map<String, DatabankCandidate> promotedCatalog = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile String activeJobId = null;
    private volatile GeneratorRequest activeConfig = null;
    private volatile String statusState = "IDLE";
    private volatile String statusMessage = "Ready to mine strategies.";
    private volatile int currentGeneration = 0;
    private volatile int maxGenerations = 0;
    private final AtomicInteger evaluatedCount = new AtomicInteger(0);
    private volatile int totalToEvaluate = 0;
    private final AtomicInteger passedCount = new AtomicInteger(0);
    private volatile double bestScoreFound = 0.0;
    private volatile long startedAtMs = 0L;
    private volatile int totalBarsLoaded = 0;
    private volatile int isBarsCount = 0;
    private volatile int oosBarsCount = 0;

    public StrategyGeneratorService() {
        this(RuntimeDataPaths.defaultDataDirectory().resolve("generator"));
    }

    public StrategyGeneratorService(Path storageDir) {
        this.storageDir = storageDir;
        this.sqliteDatabank = new SqliteStrategyDatabank(storageDir.resolve("strategies.db"));
        this.legacyDatabankFile = storageDir.resolve("databank.json");
        this.legacyPromotedFile = storageDir.resolve("promoted.json");
        loadPersistedState();
    }

    public StrategyGeneratorService(Path storageDir, Path ignoredSourceDir) {
        this(storageDir);
    }

    /**
     * Ensures all strategies persisted in {@code strategies.db} are registered in {@link StrategyCatalog}.
     */
    public static synchronized void ensureCatalogLoaded() {
        try {
            Path dir = RuntimeDataPaths.defaultDataDirectory().resolve("generator");
            Path dbFile = dir.resolve("strategies.db").toAbsolutePath().normalize();
            boolean hasLegacyJson = Files.isRegularFile(dir.resolve("databank.json"))
                || Files.isRegularFile(dir.resolve("promoted.json"));
            long mtime = Files.isRegularFile(dbFile) ? Files.getLastModifiedTime(dbFile).toMillis() : -1L;
            if (!hasLegacyJson
                && dbFile.equals(lastLoadedDbPath)
                && mtime == lastLoadedDbMtime
                && (lastLoadedFirstId == null || StrategyCatalog.contains(lastLoadedFirstId))) {
                return;
            }
            SqliteStrategyDatabank db = new SqliteStrategyDatabank(dbFile);
            List<DatabankCandidate> rows = db.loadAll();
            if (rows.isEmpty() && hasLegacyJson) {
                try (StrategyGeneratorService ignored = new StrategyGeneratorService(dir)) {
                    // Migrates legacy JSON files and registers strategies in StrategyCatalog
                }
                return;
            }
            String firstId = null;
            for (DatabankCandidate raw : rows) {
                DatabankCandidate c = stripSqPrefix(raw);
                if (c != null && c.id() != null) {
                    if (firstId == null) {
                        firstId = c.id();
                    }
                    if (!StrategyCatalog.contains(c.id())) {
                        registerCandidateInCatalog(c);
                    }
                }
            }
            lastLoadedDbPath = dbFile;
            lastLoadedDbMtime = Files.isRegularFile(dbFile) ? Files.getLastModifiedTime(dbFile).toMillis() : -1L;
            lastLoadedFirstId = firstId;
        } catch (Exception e) {
            log.debug("Could not auto-load strategies.db into StrategyCatalog: {}", e.getMessage());
        }
    }

    private void loadPersistedState() {
        try {
            Files.createDirectories(storageDir);
            boolean migratedFromJson = false;
            boolean renamedInSqlite = false;

            for (DatabankCandidate c : sqliteDatabank.loadAll()) {
                DatabankCandidate norm = stripSqPrefix(c);
                if (norm == null || norm.id() == null) {
                    continue;
                }
                if (c.id() != null && !c.id().equals(norm.id())) {
                    sqliteDatabank.delete(c.id());
                    renamedInSqlite = true;
                }
                if (databank.stream().noneMatch(existing -> existing.id().equals(norm.id()))) {
                    databank.add(norm);
                }
            }

            if (Files.isRegularFile(legacyDatabankFile)) {
                List<DatabankCandidate> loaded = MAPPER.readValue(legacyDatabankFile.toFile(), new TypeReference<>() {});
                if (loaded != null) {
                    for (DatabankCandidate c : loaded) {
                        DatabankCandidate norm = stripSqPrefix(c);
                        if (norm == null || norm.id() == null) {
                            continue;
                        }
                        if (databank.stream().noneMatch(existing -> existing.id().equals(norm.id()))) {
                            databank.add(norm);
                            migratedFromJson = true;
                        }
                    }
                }
            }
            if (Files.isRegularFile(legacyPromotedFile)) {
                List<DatabankCandidate> promoted = MAPPER.readValue(legacyPromotedFile.toFile(), new TypeReference<>() {});
                if (promoted != null) {
                    for (DatabankCandidate c : promoted) {
                        DatabankCandidate norm = stripSqPrefix(c);
                        if (norm == null || norm.id() == null) {
                            continue;
                        }
                        promotedCatalog.put(norm.id(), norm);
                        databank.removeIf(existing -> existing.id().equals(norm.id()));
                        databank.add(norm);
                        migratedFromJson = true;
                    }
                }
            }
            for (DatabankCandidate c : databank) {
                if (c.promoted()) {
                    promotedCatalog.put(c.id(), c);
                }
                registerInCatalog(c);
            }
            if (migratedFromJson || renamedInSqlite || Files.isRegularFile(legacyDatabankFile) || Files.isRegularFile(legacyPromotedFile)) {
                if (persistDatabank()) {
                    Files.deleteIfExists(legacyDatabankFile);
                    Files.deleteIfExists(legacyPromotedFile);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load persisted StrategyGenerator databank: {}", e.getMessage());
        }
    }

    private static DatabankCandidate stripSqPrefix(DatabankCandidate c) {
        if (c == null) {
            return null;
        }
        String oldId = c.id();
        String newId = oldId;
        if (oldId != null && oldId.startsWith("Sq") && oldId.length() > 4
            && (c.symbol() == null || !c.symbol().toUpperCase(Locale.ROOT).startsWith("SQ"))) {
            String candidateId = oldId.substring(2);
            if (Character.isUpperCase(candidateId.charAt(0))) {
                newId = candidateId;
            }
        }
        boolean idChanged = !java.util.Objects.equals(oldId, newId);
        boolean legacySqPath = (c.javaFilePath() != null && c.javaFilePath().contains("/sqimported/"))
            || (c.javaSourceCode() != null && c.javaSourceCode().contains("package com.martinfou.trading.strategies.sqimported;"));
        boolean splitMismatch = Math.abs((c.isNetProfit() + c.oosNetProfit()) - c.netProfit()) > 1.0
            || (c.isTrades() + c.oosTrades() != c.totalTrades());
        boolean missingDefaults = c.initialCapital() == null || c.lotSize() == null
            || c.commissionPerTrade() == null || c.slippagePct() == null
            || c.valStartIndex() == null || c.wfePct() == null
            || c.partitionBands() == null || c.partitionBands().isEmpty()
            || c.sessionSummary() == null || c.entryStartHourEt() == null
            || c.entryEndHourEt() == null || c.allowedDaysMask() == null
            || c.exitSessionMode() == null || c.flatCutoffHourEt() == null
            || splitMismatch;
        if (!idChanged && !legacySqPath && !missingDefaults) {
            return c;
        }
        int normIsTrades = Math.clamp(c.isTrades(), 1, Math.max(1, c.totalTrades() - 1));
        int normOosTrades = Math.max(1, c.totalTrades() - normIsTrades);
        double normIsNet = splitMismatch ? round2(c.netProfit() * (normIsTrades / (double) Math.max(1, c.totalTrades()))) : c.isNetProfit();
        double normOosNet = splitMismatch ? round2(c.netProfit() - normIsNet) : c.oosNetProfit();
        StrategyGenome g = c.genome();
        StrategyGenome newGenome = (g != null && idChanged) ? new StrategyGenome(
            newId, g.symbol(), g.timeframe(), g.archetype(), g.direction(), g.entryOrderType(),
            g.longRules(), g.shortRules(), g.stopEntryLookback(), g.stopEntryAtrMult(), g.stopOrderValidBars(),
            g.atrPeriod(), g.slAtrMult(), g.tpAtrMult(), g.trailingAtrMult(), g.maxBarsInTrade(),
            g.entryStartHourEt(), g.entryEndHourEt(), g.allowedDaysMask(), g.exitSessionMode(), g.flatCutoffHourEt()
        ) : g;
        String newPath = c.javaFilePath();
        if (newPath != null) {
            if (idChanged) {
                newPath = newPath.replace(oldId + ".java", newId + ".java");
            }
            newPath = newPath.replace("/sqimported/", "/generated/");
        }
        String newCode = c.javaSourceCode();
        if (newCode != null) {
            if (idChanged) {
                newCode = newCode.replace(oldId, newId);
            }
            newCode = newCode.replace(
                "package com.martinfou.trading.strategies.sqimported;",
                "package com.martinfou.trading.strategies.generated;"
            );
        }
        return new DatabankCandidate(
            newId, c.symbol(), c.timeframe(), c.yearSpec(), c.archetype(), c.direction(), c.entryOrderType(),
            c.tier(), c.compositeScore(), c.totalTrades(), normIsTrades, normOosTrades,
            c.netProfit(), normIsNet, normOosNet, c.totalReturnPct(),
            c.profitFactor(), c.isProfitFactor(), c.oosProfitFactor(),
            c.sharpeRatio(), c.isSharpeRatio(), c.oosSharpeRatio(),
            c.winRatePct(), c.maxDrawdownPct(), c.retDdRatio(), c.rSquared(),
            c.mcVar95(), c.mcLossProbPct(), c.generationFound(), c.createdAt(),
            c.promoted(), newPath, c.longSignalSummary(), c.shortSignalSummary(),
            c.entrySummary(), c.exitSummary(), c.indicators(), c.equityCurve(),
            c.oosStartIndex(), newGenome, newCode,
            c.resolvedCapital(), c.resolvedLotSize(), c.resolvedCommission(), c.resolvedSlippage(),
            c.resolvedValStartIndex(), c.resolvedValTrades(), c.resolvedValNetProfit(),
            c.resolvedValProfitFactor(), c.resolvedWfePct(),
            c.resolvedSplitFolds(), c.resolvedPartitionBands(),
            c.resolvedSessionSummary(), c.resolvedEntryStartHourEt(), c.resolvedEntryEndHourEt(),
            c.resolvedAllowedDaysMask(), c.resolvedExitSessionMode(), c.resolvedFlatCutoffHourEt()
        );
    }

    private synchronized boolean persistDatabank() {
        try {
            for (DatabankCandidate c : databank) {
                if (c.promoted()) {
                    promotedCatalog.put(c.id(), c);
                }
            }
            return sqliteDatabank.upsertAll(new ArrayList<>(databank));
        } catch (Exception e) {
            log.warn("Failed to persist generator databank to SQLite: {}", e.getMessage());
            return false;
        }
    }

    private void registerInCatalog(DatabankCandidate c) {
        registerCandidateInCatalog(c);
    }

    private static void registerCandidateInCatalog(DatabankCandidate c) {
        if (c == null || c.id() == null || c.genome() == null) {
            return;
        }
        StrategyGenome genome = c.genome();
        String desc = String.format(
            Locale.US,
            "[%s Score %.1f] %s %s %s — Long: %s | Short: %s | %s",
            c.tier(), c.compositeScore(), c.symbol(), c.timeframe(), c.archetype(),
            c.longSignalSummary(), c.shortSignalSummary(), c.exitSummary()
        );
        StrategyCatalog.registerGenerated(
            c.id(),
            StrategyCatalog.Family.GENERATED,
            c.symbol(),
            c.archetype(),
            c.indicators(),
            desc,
            c.archetype(),
            c.timeframe() != null ? List.of(c.timeframe()) : List.of("H1"),
            sym -> {
                String targetSym = (sym != null && !sym.isBlank()) ? sym : c.symbol();
                double defaultUnits = LotSizing.resolveQuantityFromLots(c.resolvedLotSize(), targetSym);
                return new StrategyCatalogFixedQtyWrapper(genome.toExecutableStrategy(targetSym), defaultUnits);
            }
        );
    }

    public Map<String, Object> getStatus() {
        long elapsedMs = startedAtMs > 0
            ? (running.get() ? System.currentTimeMillis() - startedAtMs : Math.max(1L, System.currentTimeMillis() - startedAtMs))
            : 0L;
        int evals = evaluatedCount.get();
        double speed = elapsedMs > 0 ? (evals * 1000.0) / elapsedMs : 0.0;
        int pct = totalToEvaluate > 0 ? Math.clamp((evals * 100) / totalToEvaluate, 0, 100) : 0;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", running.get());
        map.put("jobId", activeJobId);
        map.put("state", statusState);
        map.put("message", statusMessage);
        map.put("progressPct", running.get() ? pct : ("COMPLETED".equals(statusState) ? 100 : pct));
        map.put("currentGeneration", currentGeneration);
        map.put("maxGenerations", maxGenerations);
        map.put("evaluatedCount", evals);
        map.put("totalToEvaluate", totalToEvaluate);
        map.put("evalSpeedPerSec", Math.round(speed * 10.0) / 10.0);
        map.put("passedCount", passedCount.get());
        map.put("bestScoreFound", Math.round(bestScoreFound * 10.0) / 10.0);
        map.put("databankCount", databank.size());
        map.put("totalBarsLoaded", totalBarsLoaded);
        map.put("isBarsCount", isBarsCount);
        map.put("oosBarsCount", oosBarsCount);
        map.put("config", activeConfig);
        return map;
    }

    public List<DatabankCandidate> listDatabank() {
        List<DatabankCandidate> copy = new ArrayList<>(databank);
        copy.sort(Comparator.comparingDouble(DatabankCandidate::compositeScore).reversed());
        return copy;
    }

    public Optional<DatabankCandidate> getCandidate(String id) {
        return databank.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public void deleteCandidate(String id) {
        databank.removeIf(c -> c.id().equals(id));
        promotedCatalog.remove(id);
        StrategyCatalog.unregister(id);
        sqliteDatabank.delete(id);
    }

    public void clearDatabank() {
        databank.clear();
        promotedCatalog.clear();
        StrategyCatalog.unregisterAllGenerated();
        sqliteDatabank.deleteAll();
    }

    public DatabankCandidate promoteToCatalog(String id) throws IOException {
        DatabankCandidate existing = getCandidate(id)
            .orElseThrow(() -> new IllegalArgumentException("Candidate not found in databank: " + id));

        String storageUri = "sqlite://strategies.db#" + existing.id();
        String sourceToWrite = existing.javaSourceCode() != null
            ? existing.javaSourceCode().replace(
                "package com.martinfou.trading.strategies.sqimported;",
                "package com.martinfou.trading.strategies.generated;"
            )
            : "";

        DatabankCandidate updated = new DatabankCandidate(
            existing.id(),
            existing.symbol(),
            existing.timeframe(),
            existing.yearSpec(),
            existing.archetype(),
            existing.direction(),
            existing.entryOrderType(),
            existing.tier(),
            existing.compositeScore(),
            existing.totalTrades(),
            existing.isTrades(),
            existing.oosTrades(),
            existing.netProfit(),
            existing.isNetProfit(),
            existing.oosNetProfit(),
            existing.totalReturnPct(),
            existing.profitFactor(),
            existing.isProfitFactor(),
            existing.oosProfitFactor(),
            existing.sharpeRatio(),
            existing.isSharpeRatio(),
            existing.oosSharpeRatio(),
            existing.winRatePct(),
            existing.maxDrawdownPct(),
            existing.retDdRatio(),
            existing.rSquared(),
            existing.mcVar95(),
            existing.mcLossProbPct(),
            existing.generationFound(),
            existing.createdAt(),
            true,
            storageUri,
            existing.longSignalSummary(),
            existing.shortSignalSummary(),
            existing.entrySummary(),
            existing.exitSummary(),
            existing.indicators(),
            existing.equityCurve(),
            existing.oosStartIndex(),
            existing.genome(),
            sourceToWrite,
            existing.resolvedCapital(),
            existing.resolvedLotSize(),
            existing.resolvedCommission(),
            existing.resolvedSlippage(),
            existing.resolvedValStartIndex(),
            existing.resolvedValTrades(),
            existing.resolvedValNetProfit(),
            existing.resolvedValProfitFactor(),
            existing.resolvedWfePct(),
            existing.resolvedSplitFolds(),
            existing.resolvedPartitionBands(),
            existing.resolvedSessionSummary(),
            existing.resolvedEntryStartHourEt(),
            existing.resolvedEntryEndHourEt(),
            existing.resolvedAllowedDaysMask(),
            existing.resolvedExitSessionMode(),
            existing.resolvedFlatCutoffHourEt()
        );

        for (int i = 0; i < databank.size(); i++) {
            if (databank.get(i).id().equals(id)) {
                databank.set(i, updated);
                break;
            }
        }
        promotedCatalog.put(updated.id(), updated);
        registerInCatalog(updated);
        sqliteDatabank.upsert(updated);
        log.info("Promoted strategy {} in SQLite databank ({})", updated.id(), sqliteDatabank.dbPath());
        return updated;
    }

    public void stop() {
        if (running.get()) {
            stopRequested.set(true);
            statusMessage = "Stopping after current generation...";
        }
    }

    public String start(GeneratorRequest rawReq) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Strategy Generator is already running.");
        }
        stopRequested.set(false);

        String symbol = (rawReq.symbol() != null && !rawReq.symbol().isBlank()) ? rawReq.symbol().trim().toUpperCase(Locale.ROOT) : "MGC";
        String tf = (rawReq.timeframe() != null && !rawReq.timeframe().isBlank()) ? rawReq.timeframe().trim().toUpperCase(Locale.ROOT) : "M30";
        String yearSpec = (rawReq.yearSpec() != null && !rawReq.yearSpec().isBlank()) ? rawReq.yearSpec().trim() : "2025-2026";
        int oosSplitPct = rawReq.oosSplitPct() != null ? Math.clamp(rawReq.oosSplitPct(), 10, 50) : 30;
        String searchMode = (rawReq.searchMode() != null && !rawReq.searchMode().isBlank()) ? rawReq.searchMode().toUpperCase(Locale.ROOT) : "GENETIC";
        String archetype = (rawReq.archetype() != null && !rawReq.archetype().isBlank()) ? rawReq.archetype().toUpperCase(Locale.ROOT) : "ALL";
        String direction = (rawReq.direction() != null && !rawReq.direction().isBlank()) ? rawReq.direction().toUpperCase(Locale.ROOT) : "BOTH";
        String entryOrderType = (rawReq.entryOrderType() != null && !rawReq.entryOrderType().isBlank()) ? rawReq.entryOrderType().toUpperCase(Locale.ROOT) : "STOP";
        int popSize = rawReq.populationSize() != null ? Math.clamp(rawReq.populationSize(), 10, 200) : 50;
        int gens = rawReq.generations() != null ? Math.clamp(rawReq.generations(), 1, 50) : 10;
        List<String> blocks = (rawReq.buildingBlocks() != null && !rawReq.buildingBlocks().isEmpty())
            ? rawReq.buildingBlocks()
            : java.util.Arrays.stream(BlockType.values()).map(Enum::name).toList();
        int minTrades = rawReq.minTrades() != null ? Math.max(1, rawReq.minTrades()) : 15;
        double minPf = rawReq.minProfitFactor() != null ? rawReq.minProfitFactor() : 1.15;
        double minOosPf = rawReq.minOosProfitFactor() != null ? rawReq.minOosProfitFactor() : 1.05;
        double minSharpe = rawReq.minSharpe() != null ? rawReq.minSharpe() : 0.3;
        double maxDd = rawReq.maxDrawdownPct() != null ? rawReq.maxDrawdownPct() : 25.0;
        LotSizing.InstrumentDefaults defaults = LotSizing.defaultsForSymbol(symbol);
        double capital = rawReq.initialCapital() != null && rawReq.initialCapital() > 0 ? rawReq.initialCapital() : defaults.capital();
        double lotSize = rawReq.lotSize() != null && rawReq.lotSize() > 0 ? rawReq.lotSize() : defaults.lotSize();
        double commission = rawReq.commissionPerTrade() != null && rawReq.commissionPerTrade() >= 0 ? rawReq.commissionPerTrade() : defaults.commissionPerTrade();
        double slippage = rawReq.slippagePct() != null && rawReq.slippagePct() >= 0 ? rawReq.slippagePct() : defaults.slippagePct();
        int splitFolds = rawReq.splitFolds() != null ? Math.clamp(rawReq.splitFolds(), 1, 6) : 3;
        String sessionMode = (rawReq.sessionMode() != null && !rawReq.sessionMode().isBlank())
            ? rawReq.sessionMode().trim().toUpperCase(Locale.ROOT)
            : "CONTINUOUS";
        int entryStartHourEt = rawReq.entryStartHourEt() != null ? Math.clamp(rawReq.entryStartHourEt(), 0, 23) : 0;
        int entryEndHourEt = rawReq.entryEndHourEt() != null ? Math.clamp(rawReq.entryEndHourEt(), 1, 24) : 24;
        if (entryEndHourEt == entryStartHourEt) {
            entryEndHourEt = Math.min(24, entryStartHourEt + 1);
        }
        int allowedDaysMask = (rawReq.allowedDaysMask() != null && (rawReq.allowedDaysMask() & 31) != 0)
            ? (rawReq.allowedDaysMask() & 31)
            : 31;
        String exitSessionMode = (rawReq.exitSessionMode() != null && !rawReq.exitSessionMode().isBlank())
            ? rawReq.exitSessionMode().trim().toUpperCase(Locale.ROOT)
            : "CONTINUOUS";
        int flatCutoffHourEt = rawReq.flatCutoffHourEt() != null ? Math.clamp(rawReq.flatCutoffHourEt(), 12, 17) : 16;

        GeneratorRequest req = new GeneratorRequest(
            symbol, tf, yearSpec, oosSplitPct, searchMode, archetype, direction, entryOrderType,
            popSize, gens, blocks, minTrades, minPf, minOosPf, minSharpe, maxDd,
            capital, lotSize, commission, slippage, splitFolds,
            sessionMode, entryStartHourEt, entryEndHourEt, allowedDaysMask, exitSessionMode, flatCutoffHourEt
        );

        String jobId = UUID.randomUUID().toString();
        this.activeJobId = jobId;
        this.activeConfig = req;
        this.statusState = "RUNNING";
        this.statusMessage = "Loading historical bars for " + symbol + " (" + tf + ", " + yearSpec + ")...";
        this.currentGeneration = 0;
        this.maxGenerations = gens;
        this.evaluatedCount.set(0);
        this.totalToEvaluate = popSize * gens;
        this.passedCount.set(0);
        this.bestScoreFound = 0.0;
        this.startedAtMs = System.currentTimeMillis();

        jobExecutor.submit(() -> executeMiningJob(jobId, req));
        return jobId;
    }

    public static List<PartitionBand> buildSampledBands(int totalLength, int splitFolds, int oosSplitPct) {
        if (totalLength < 2) {
            return List.of();
        }
        int folds = Math.clamp(splitFolds, 1, 6);
        int oosPct = Math.clamp(oosSplitPct, 10, 50);
        int maxIdx = totalLength - 1;
        List<PartitionBand> bands = new ArrayList<>(folds * 2);

        if (folds <= 1) {
            int splitIdx = Math.clamp((int) Math.round(maxIdx * (100 - oosPct) / 100.0), 1, Math.max(1, maxIdx - 1));
            bands.add(new PartitionBand(0, splitIdx, false, "IS"));
            bands.add(new PartitionBand(splitIdx, maxIdx, true, "OOS"));
            return bands;
        }

        for (int k = 0; k < folds; k++) {
            int cycleStart = (int) Math.round((k / (double) folds) * maxIdx);
            int cycleEnd = (k == folds - 1) ? maxIdx : (int) Math.round(((k + 1) / (double) folds) * maxIdx);
            int cycleLen = Math.max(2, cycleEnd - cycleStart);
            int isEnd = Math.clamp(
                cycleStart + (int) Math.round(cycleLen * (100 - oosPct) / 100.0),
                cycleStart + 1,
                Math.max(cycleStart + 1, cycleEnd - 1)
            );
            bands.add(new PartitionBand(cycleStart, isEnd, false, "IS" + (k + 1)));
            bands.add(new PartitionBand(isEnd, cycleEnd, true, "OOS" + (k + 1)));
        }
        return bands;
    }

    private void executeMiningJob(String jobId, GeneratorRequest req) {
        try {
            List<Bar> bars = loadOrGenerateBars(req.symbol(), req.timeframe(), req.yearSpec());
            if (bars.size() < 150) {
                throw new IllegalStateException("Not enough historical bars (" + bars.size() + ") for " + req.symbol() + " " + req.timeframe());
            }

            this.totalBarsLoaded = bars.size();
            int folds = req.splitFolds() != null ? Math.clamp(req.splitFolds(), 1, 6) : 3;
            List<PartitionBand> barBands = buildSampledBands(bars.size(), folds, req.oosSplitPct());

            int isCount = 0;
            int oosCount = 0;
            for (PartitionBand b : barBands) {
                int span = Math.max(0, b.endIndex() - b.startIndex());
                if (b.oos()) {
                    oosCount += span;
                } else {
                    isCount += span;
                }
            }
            this.isBarsCount = isCount;
            this.oosBarsCount = oosCount;

            long[] barEpochMillis = new long[bars.size()];
            for (int i = 0; i < bars.size(); i++) {
                barEpochMillis[i] = bars.get(i).timestamp().toEpochMilli();
            }

            List<BlockType> allowedBlocks = parseAllowedBlocks(req.buildingBlocks());
            int popSize = req.populationSize();
            int gens = req.generations();

            List<StrategyGenome> population = new ArrayList<>(popSize);
            for (int i = 0; i < popSize; i++) {
                population.add(createRandomGenome(req, allowedBlocks));
            }

            try (ExecutorService evalPool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int gen = 1; gen <= gens; gen++) {
                    if (stopRequested.get()) {
                        break;
                    }
                    this.currentGeneration = gen;
                    this.statusMessage = String.format(
                        Locale.US,
                        "Evolving Generation %d / %d on %s %s (%,d bars across %d alternating IS/OOS bands: %,d IS / %,d Blind OOS)...",
                        gen, gens, req.symbol(), req.timeframe(), bars.size(),
                        barBands.size(), isBarsCount, oosBarsCount
                    );

                    final int genNum = gen;
                    final int finalIsBars = isCount;
                    final int finalOosBars = oosCount;
                    List<CompletableFuture<ScoredGenome>> futures = new ArrayList<>(population.size());
                    for (StrategyGenome genome : population) {
                        futures.add(CompletableFuture.supplyAsync(
                            () -> evaluateCandidate(genome, req, bars, barEpochMillis, barBands, folds, finalIsBars, finalOosBars, genNum),
                            evalPool
                        ));
                    }

                    List<ScoredGenome> results = new ArrayList<>(population.size());
                    for (CompletableFuture<ScoredGenome> f : futures) {
                        ScoredGenome sg = f.join();
                        if (sg != null) {
                            results.add(sg);
                            if (sg.fitness > bestScoreFound) {
                                bestScoreFound = sg.fitness;
                            }
                            if (sg.candidate != null) {
                                boolean isDuplicate = databank.stream().anyMatch(existing ->
                                    existing.id().equals(sg.candidate.id()) || (
                                        existing.symbol().equals(sg.candidate.symbol())
                                        && existing.timeframe().equals(sg.candidate.timeframe())
                                        && existing.entryOrderType().equals(sg.candidate.entryOrderType())
                                        && existing.longSignalSummary().equals(sg.candidate.longSignalSummary())
                                        && existing.shortSignalSummary().equals(sg.candidate.shortSignalSummary())
                                        && existing.entrySummary().equals(sg.candidate.entrySummary())
                                        && existing.exitSummary().equals(sg.candidate.exitSummary())
                                        && existing.resolvedSessionSummary().equals(sg.candidate.resolvedSessionSummary())
                                    )
                                );
                                if (!isDuplicate) {
                                    databank.add(sg.candidate);
                                    registerInCatalog(sg.candidate);
                                    passedCount.incrementAndGet();
                                }
                            }
                        }
                    }

                    persistDatabank();

                    if (gen < gens && !stopRequested.get()) {
                        results.sort(Comparator.comparingDouble(ScoredGenome::fitness).reversed());
                        population = breedNextGeneration(results, req, allowedBlocks, popSize);
                    }
                }
            }

            if (stopRequested.get()) {
                this.statusState = "STOPPED";
                this.statusMessage = String.format(
                    Locale.US,
                    "Stopped by user after %,d evaluations (%d strategies added to Databank).",
                    evaluatedCount.get(), passedCount.get()
                );
            } else {
                this.statusState = "COMPLETED";
                this.statusMessage = String.format(
                    Locale.US,
                    "Completed %,d evaluations across %d generations — %d strategies passed into Databank.",
                    evaluatedCount.get(), maxGenerations, passedCount.get()
                );
            }
        } catch (Exception e) {
            log.error("Strategy Generator job {} failed", jobId, e);
            this.statusState = "FAILED";
            this.statusMessage = "Generator error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            running.set(false);
            persistDatabank();
        }
    }

    private record ScoredGenome(StrategyGenome genome, double fitness, DatabankCandidate candidate) {}

    private ScoredGenome evaluateCandidate(
        StrategyGenome genome,
        GeneratorRequest req,
        List<Bar> fullBars,
        long[] barEpochMillis,
        List<PartitionBand> barBands,
        int splitFolds,
        int isBarsTotal,
        int oosBarsTotal,
        int generation
    ) {
        if (stopRequested.get()) {
            return null;
        }
        try {
            double quantityUnits = LotSizing.resolveQuantityFromLots(req.lotSize(), req.symbol());

            Strategy fullStrat = new StrategyCatalogFixedQtyWrapper(genome.toExecutableStrategy(req.symbol(), 60), quantityUnits);
            BacktestResult fullResult = new BacktestEngine(fullStrat, fullBars, req.initialCapital())
                .withCommissionFixed(req.commissionPerTrade())
                .withSlippagePct(req.slippagePct())
                .run();

            evaluatedCount.incrementAndGet();

            List<Trade> allTrades = fullResult.trades() != null ? fullResult.trades() : List.of();
            if (allTrades.isEmpty()) {
                return new ScoredGenome(genome, 0.0, null);
            }

            int numBands = barBands.size();
            int[] bandTrades = new int[numBands];
            double[] bandGrossWin = new double[numBands];
            double[] bandGrossLoss = new double[numBands];
            double[] bandNetPnl = new double[numBands];

            int isTrades = 0;
            double isGrossWin = 0.0;
            double isGrossLoss = 0.0;
            double isNetProfit = 0.0;
            double isRunningEq = req.initialCapital();
            double isPeakEq = req.initialCapital();
            double isMaxDdPct = 0.0;
            List<Double> isTradeCurve = new ArrayList<>();
            isTradeCurve.add(req.initialCapital());
            List<Double> isTradeReturns = new ArrayList<>();

            int oosTrades = 0;
            double oosGrossWin = 0.0;
            double oosGrossLoss = 0.0;
            double oosNetProfit = 0.0;
            double oosRunningEq = req.initialCapital();
            List<Double> oosTradeReturns = new ArrayList<>();

            for (Trade t : allTrades) {
                long entryMs = t.entryTime() != null ? t.entryTime().toEpochMilli() : 0L;
                int barIdx = java.util.Arrays.binarySearch(barEpochMillis, entryMs);
                if (barIdx < 0) {
                    barIdx = Math.clamp(-barIdx - 1, 0, fullBars.size() - 1);
                }
                int matchedBand = 0;
                for (int b = 0; b < numBands; b++) {
                    PartitionBand pb = barBands.get(b);
                    if (barIdx >= pb.startIndex() && (barIdx < pb.endIndex() || b == numBands - 1)) {
                        matchedBand = b;
                        break;
                    }
                }
                double pnl = t.pnl();
                bandTrades[matchedBand]++;
                bandNetPnl[matchedBand] += pnl;
                if (pnl > 0) {
                    bandGrossWin[matchedBand] += pnl;
                } else if (pnl < 0) {
                    bandGrossLoss[matchedBand] += Math.abs(pnl);
                }

                if (barBands.get(matchedBand).oos()) {
                    oosTrades++;
                    oosNetProfit += pnl;
                    if (pnl > 0) oosGrossWin += pnl;
                    else if (pnl < 0) oosGrossLoss += Math.abs(pnl);
                    oosTradeReturns.add(pnl / Math.max(1.0, oosRunningEq));
                    oosRunningEq += pnl;
                } else {
                    isTrades++;
                    isNetProfit += pnl;
                    if (pnl > 0) isGrossWin += pnl;
                    else if (pnl < 0) isGrossLoss += Math.abs(pnl);
                    isTradeReturns.add(pnl / Math.max(1.0, isRunningEq));
                    isRunningEq += pnl;
                    isTradeCurve.add(isRunningEq);
                    if (isRunningEq > isPeakEq) {
                        isPeakEq = isRunningEq;
                    } else if (isPeakEq > 0) {
                        double dd = ((isPeakEq - isRunningEq) / isPeakEq) * 100.0;
                        if (dd > isMaxDdPct) {
                            isMaxDdPct = dd;
                        }
                    }
                }
            }

            int minIsTrades = Math.max(4, (int) Math.round(req.minTrades() * ((100 - req.oosSplitPct()) / 100.0) * 0.65));
            if (isTrades < minIsTrades) {
                return new ScoredGenome(genome, isTrades * 0.5, null);
            }

            double isPf = isGrossLoss > 0.01 ? isGrossWin / isGrossLoss : (isGrossWin > 0 ? 5.0 : 0.0);
            double oosPf = oosGrossLoss > 0.01 ? oosGrossWin / oosGrossLoss : (oosGrossWin > 0 ? 5.0 : 0.0);

            // Last IS band serves as internal cross-validation fold (or fold-to-fold IS consistency)
            int lastIsBandIdx = 0;
            int profitableIsBands = 0;
            int profitableOosBands = 0;
            for (int b = 0; b < numBands; b++) {
                if (barBands.get(b).oos()) {
                    if (bandNetPnl[b] >= 0 && bandTrades[b] > 0) {
                        profitableOosBands++;
                    }
                } else {
                    lastIsBandIdx = b;
                    if (bandNetPnl[b] > 0) {
                        profitableIsBands++;
                    }
                }
            }

            int valTrades = bandTrades[lastIsBandIdx];
            double valNetProfit = bandNetPnl[lastIsBandIdx];
            double valPf = bandGrossLoss[lastIsBandIdx] > 0.01
                ? bandGrossWin[lastIsBandIdx] / bandGrossLoss[lastIsBandIdx]
                : (bandGrossWin[lastIsBandIdx] > 0 ? 4.0 : 0.0);

            double isSharpe = computeTradeSharpe(isTradeReturns);
            double oosSharpe = computeTradeSharpe(oosTradeReturns);
            double isR2 = computeEquityR2(isTradeCurve);

            // Evolutionary Fitness: Strictly computed from In-Sample bands (IS_1 .. IS_K) with ZERO OOS leakage
            int ruleCount = genome.longRules() != null ? genome.longRules().size() : 1;
            boolean autoScheduleSearch = "AUTO".equalsIgnoreCase(req.sessionMode()) || "AUTO".equalsIgnoreCase(req.exitSessionMode());
            double sessionPenalty = (autoScheduleSearch && genome.hasRestrictedSchedule()) ? 1.5 : 0.0;
            double complexityPenalty = Math.max(0, ruleCount - 2) * 1.5 + sessionPenalty;
            double isPfScore = Math.min(34.0, Math.max(0.0, (isPf - 1.0) * 32.0));
            double isBandConsistencyRatio = splitFolds > 0 ? (profitableIsBands / (double) splitFolds) : 1.0;
            double valConsistencyScore = isBandConsistencyRatio * 14.0
                + (valNetProfit > 0 ? Math.min(10.0, Math.max(0.0, (valPf - 1.0) * 12.0)) : 0.0);
            double isSharpeScore = Math.min(18.0, Math.max(0.0, isSharpe * 10.0));
            double isR2Score = Math.min(12.0, Math.max(0.0, isR2 * 12.0));
            double isDdScore = Math.max(0.0, 12.0 - Math.max(0.0, isMaxDdPct - 5.0) * 0.45);
            double evolutionaryFitness = isNetProfit > 0
                ? Math.clamp(isPfScore + valConsistencyScore + isSharpeScore + isR2Score + isDdScore - complexityPenalty, 1.0, 99.5)
                : Math.max(0.5, isPf * 6.0);

            if (isNetProfit <= 0 || isPf < 1.02) {
                return new ScoredGenome(genome, evolutionaryFitness * 0.55, null);
            }

            double sharpe = sanitizeMetric(fullResult.sharpeRatio());
            double maxDdPct = sanitizeMetric(fullResult.maxDrawdownPct());
            double r2 = computeEquityR2(fullResult.equityCurve());
            double netProfit = fullResult.finalEquity() - req.initialCapital();
            double residualOpenPnl = netProfit - (isNetProfit + oosNetProfit);
            if (Math.abs(residualOpenPnl) > 0.005) {
                oosNetProfit += residualOpenPnl;
            }
            double pf = sanitizeMetric(fullResult.profitFactor());
            double totalReturnPct = sanitizeMetric(fullResult.totalReturnPct());
            double winRatePct = sanitizeMetric(fullResult.winRatePct());
            double retDd = maxDdPct > 0.25 ? totalReturnPct / maxDdPct : (totalReturnPct > 0 ? totalReturnPct * 2.0 : 0.0);

            // Walk-Forward / OOS Retention Efficiency (WFE %) across all alternating bands
            double isReturnPerBar = isNetProfit / Math.max(1.0, isBarsTotal);
            double oosReturnPerBar = oosNetProfit / Math.max(1.0, oosBarsTotal);
            double pfRetentionPct = isPf > 0 ? (oosPf / isPf) * 100.0 : 0.0;
            double returnRetentionPct = (isReturnPerBar > 0 && oosReturnPerBar > 0)
                ? Math.min(160.0, (oosReturnPerBar / isReturnPerBar) * 100.0)
                : 0.0;
            double wfePct = round2(Math.clamp(0.55 * pfRetentionPct + 0.45 * returnRetentionPct, 0.0, 200.0));

            double compositeScore = computeCompositeScore(
                pf, isPf, oosPf, sharpe, maxDdPct, retDd, r2, fullResult.totalTrades(), oosTrades, netProfit
            );

            // Proportional Minimum OOS Trades & Multi-Band OOS Consistency Gate
            int minOosTrades = Math.max(3, (int) Math.round(req.minTrades() * (req.oosSplitPct() / 100.0) * 0.60));
            int minProfitableOosBands = splitFolds >= 2 ? Math.max(1, (splitFolds + 1) / 2) : 1;
            boolean passesOosGate = req.minOosProfitFactor() <= 0.0
                || (oosTrades >= minOosTrades
                    && oosNetProfit > 0
                    && oosPf >= req.minOosProfitFactor()
                    && wfePct >= 45.0
                    && profitableOosBands >= minProfitableOosBands);
            boolean passes = fullResult.totalTrades() >= req.minTrades()
                && isNetProfit > 0
                && pf >= req.minProfitFactor()
                && passesOosGate
                && sharpe >= req.minSharpe()
                && maxDdPct <= req.maxDrawdownPct()
                && netProfit > 0;

            if (!passes) {
                return new ScoredGenome(genome, evolutionaryFitness, null);
            }

            // Monte Carlo simulation (80 runs for speed)
            double mcVar95 = 0.0;
            double mcLossProb = 0.0;
            try {
                if (allTrades.size() >= 6) {
                    MonteCarloSimulation.Result mc = new MonteCarloSimulation(fullResult, 80, 3).run();
                    mcVar95 = sanitizeMetric(mc.var95());
                    mcLossProb = sanitizeMetric(mc.probabilityOfLoss());
                }
            } catch (Exception ignored) {}

            List<Double> sampledCurve = sampleCurve(fullResult.equityCurve(), 140);
            List<PartitionBand> sampledBands = buildSampledBands(sampledCurve.size(), splitFolds, req.oosSplitPct());
            int sampledOosIdx = (int) Math.round(((100 - req.oosSplitPct()) / 100.0) * Math.max(1, sampledCurve.size() - 1));
            int sampledValIdx = Math.max(1, (int) Math.round(sampledOosIdx * (50.0 / 70.0)));

            String tier = compositeScore >= 82.0 ? "ELITE"
                : (compositeScore >= 68.0 ? "INSTITUTIONAL" : "QUALIFIED");

            String javaCode = genome.toJavaSourceCode(
                netProfit, pf, oosPf, sharpe, maxDdPct, fullResult.totalTrades(), compositeScore
            );

            DatabankCandidate candidate = new DatabankCandidate(
                genome.id(),
                genome.symbol(),
                genome.timeframe(),
                req.yearSpec(),
                genome.archetype(),
                genome.direction(),
                genome.entryOrderType(),
                tier,
                round2(compositeScore),
                fullResult.totalTrades(),
                isTrades,
                oosTrades,
                round2(netProfit),
                round2(isNetProfit),
                round2(oosNetProfit),
                round2(totalReturnPct),
                round2(pf),
                round2(isPf),
                round2(oosPf),
                round2(sharpe),
                round2(isSharpe),
                round2(oosSharpe),
                round2(winRatePct),
                round2(maxDdPct),
                round2(retDd),
                round2(r2),
                round2(mcVar95),
                round2(mcLossProb),
                generation,
                Instant.now(),
                false,
                "sqlite://strategies.db#" + genome.id(),
                genome.longSignalSummary(),
                genome.shortSignalSummary(),
                genome.entrySummary(),
                genome.exitSummary(),
                genome.indicatorsUsed(),
                sampledCurve,
                sampledOosIdx,
                genome,
                javaCode,
                req.initialCapital(),
                req.lotSize(),
                req.commissionPerTrade(),
                req.slippagePct(),
                sampledValIdx,
                valTrades,
                round2(valNetProfit),
                round2(valPf),
                wfePct,
                splitFolds,
                sampledBands,
                genome.sessionSummary(),
                genome.entryStartHourEt(),
                genome.entryEndHourEt(),
                genome.allowedDaysMask(),
                genome.exitSessionMode(),
                genome.flatCutoffHourEt()
            );

            return new ScoredGenome(genome, evolutionaryFitness, candidate);
        } catch (Exception e) {
            log.debug("Candidate evaluation failed: {}", e.getMessage());
            return new ScoredGenome(genome, 0.0, null);
        }
    }

    private List<Bar> loadOrGenerateBars(String symbol, String timeframe, String yearSpec) throws IOException {
        BarSourceResolver.BarsSource src = new BarSourceResolver.BarsSource("year", null, yearSpec, null);
        try {
            List<Bar> loaded = BarSourceResolver.load(src, symbol, timeframe);
            if (!loaded.isEmpty()) {
                return loaded;
            }
        } catch (IOException ignored) {
            // Fall through to auto-generate if missing
        }
        int currentYear = java.time.LocalDate.now().getYear();
        List<Bar> generated = HistoricalDataService.generateMultiAssetBars(symbol, currentYear, timeframe);
        com.martinfou.trading.data.BarStore store = new com.martinfou.trading.data.BarStore(
            symbol, timeframe + "_" + currentYear, RuntimeDataPaths.defaultBarsDirectory()
        );
        store.write(generated);
        return generated;
    }

    private List<BlockType> parseAllowedBlocks(List<String> names) {
        List<BlockType> list = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                try {
                    list.add(BlockType.valueOf(n.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (list.isEmpty()) {
            list.addAll(List.of(BlockType.values()));
        }
        return list;
    }

    private StrategyGenome createRandomGenome(GeneratorRequest req, List<BlockType> allowedBlocks) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String[] archetypes = {"BREAKOUT", "TREND_FOLLOWING", "MEAN_REVERSION", "MOMENTUM", "PULLBACK"};
        String arch = (req.archetype() == null || "ALL".equalsIgnoreCase(req.archetype()))
            ? archetypes[rng.nextInt(archetypes.length)]
            : req.archetype().toUpperCase(Locale.ROOT);

        String cleanSym = formatCamelSymbol(req.symbol());
        String cleanArch = formatCamelArchetype(arch);
        int serial = rng.nextInt(10000, 99999);
        String id = cleanSym + req.timeframe().toUpperCase(Locale.ROOT) + cleanArch + serial;

        String orderType = "ALL".equalsIgnoreCase(req.entryOrderType())
            ? (rng.nextBoolean() ? "STOP" : "MARKET")
            : req.entryOrderType().toUpperCase(Locale.ROOT);

        int ruleCount = rng.nextInt(1, 3);
        List<RuleGene> longRules = new ArrayList<>(ruleCount);
        List<RuleGene> shortRules = new ArrayList<>(ruleCount);

        for (int i = 0; i < ruleCount; i++) {
            BlockType bt = pickBlockForArchetype(arch, allowedBlocks, i, rng);
            RuleGene rg = randomRuleForBlock(bt, arch, rng);
            longRules.add(rg);
            shortRules.add(rg);
        }

        int[] lookbacks = {10, 14, 20, 28, 40, 50};
        int stopLookback = lookbacks[rng.nextInt(lookbacks.length)];
        double stopAtrMult = round2(rng.nextDouble(0.15, 1.25));
        int validBars = rng.nextInt(2, 8);
        int atrPeriod = rng.nextBoolean() ? 14 : 20;
        double slAtr = round2(rng.nextDouble(1.3, 3.0));
        double tpAtr = round2(slAtr * rng.nextDouble(1.3, 2.8));
        double trailAtr = rng.nextDouble() < 0.35 ? round2(rng.nextDouble(1.6, 3.2)) : 0.0;
        int maxBars = rng.nextInt(14, 64);

        boolean autoSession = "AUTO".equalsIgnoreCase(req.sessionMode());
        boolean higherTf = StrategyGenome.isHigherTimeframe(req.timeframe());
        int startHourEt = req.entryStartHourEt() != null ? req.entryStartHourEt() : 0;
        int endHourEt = req.entryEndHourEt() != null ? req.entryEndHourEt() : 24;
        int daysMask = req.allowedDaysMask() != null ? req.allowedDaysMask() : 31;
        String exitMode = req.exitSessionMode() != null ? req.exitSessionMode() : "CONTINUOUS";
        int cutoffHourEt = req.flatCutoffHourEt() != null ? req.flatCutoffHourEt() : 16;

        if (autoSession) {
            int[] structuredMasks = {31, 31, 15, 30, 14};
            daysMask = structuredMasks[rng.nextInt(structuredMasks.length)];
            if (higherTf) {
                startHourEt = 0;
                endHourEt = 24;
            } else {
                int[][] structuredWindows = {{0, 24}, {9, 16}, {9, 15}, {8, 12}, {3, 8}, {8, 11}};
                int[] win = structuredWindows[rng.nextInt(structuredWindows.length)];
                startHourEt = win[0];
                endHourEt = win[1];
            }
        }
        if (autoSession || "AUTO".equalsIgnoreCase(exitMode)) {
            if (higherTf) {
                String[] htfExits = {"CONTINUOUS", "WEEKEND_FLAT"};
                exitMode = htfExits[rng.nextInt(htfExits.length)];
            } else {
                String[] exits = {"CONTINUOUS", "INTRADAY_FLAT", "WEEKEND_FLAT"};
                exitMode = exits[rng.nextInt(exits.length)];
            }
        }

        return new StrategyGenome(
            id, req.symbol(), req.timeframe(), arch, req.direction(), orderType,
            longRules, shortRules, stopLookback, stopAtrMult, validBars,
            atrPeriod, slAtr, tpAtr, trailAtr, maxBars,
            startHourEt, endHourEt, daysMask, exitMode, cutoffHourEt
        );
    }

    private BlockType pickBlockForArchetype(String arch, List<BlockType> allowed, int slot, ThreadLocalRandom rng) {
        if (slot == 0) {
            BlockType[] preferredPool = switch (arch) {
                case "BREAKOUT" -> new BlockType[]{
                    BlockType.DONCHIAN_BREAKOUT, BlockType.KELTNER_CHANNEL, BlockType.BB_KC_SQUEEZE,
                    BlockType.INSIDE_BAR_COIL, BlockType.BOLLINGER_BANDS, BlockType.ATR_EXPANSION
                };
                case "TREND_FOLLOWING" -> new BlockType[]{
                    BlockType.MA_CROSSOVER, BlockType.SUPERTREND, BlockType.LINEAR_REG_SLOPE,
                    BlockType.VWAP_ANCHOR, BlockType.HIGHER_HIGHS_SWING, BlockType.MACD_HISTOGRAM
                };
                case "MEAN_REVERSION" -> new BlockType[]{
                    BlockType.RSI_FILTER, BlockType.STOCHASTIC_OSC, BlockType.WILLIAMS_R,
                    BlockType.ZSCORE_EXTREME, BlockType.CCI_OSCILLATOR, BlockType.BOLLINGER_BANDS
                };
                case "MOMENTUM" -> new BlockType[]{
                    BlockType.SUPERTREND, BlockType.MOMENTUM_ROC, BlockType.MACD_HISTOGRAM,
                    BlockType.CCI_OSCILLATOR, BlockType.ENGULFING_PATTERN, BlockType.VOLUME_SURGE
                };
                case "PULLBACK" -> new BlockType[]{
                    BlockType.PRICE_VS_MA, BlockType.VWAP_ANCHOR, BlockType.STOCHASTIC_OSC,
                    BlockType.ENGULFING_PATTERN, BlockType.HIGHER_HIGHS_SWING, BlockType.RSI_FILTER
                };
                default -> BlockType.values();
            };
            List<BlockType> matching = new ArrayList<>();
            for (BlockType b : preferredPool) {
                if (allowed.contains(b)) {
                    matching.add(b);
                }
            }
            if (!matching.isEmpty()) {
                return matching.get(rng.nextInt(matching.size()));
            }
        }
        return allowed.get(rng.nextInt(allowed.size()));
    }

    private RuleGene randomRuleForBlock(BlockType bt, String arch, ThreadLocalRandom rng) {
        int[] fastPeriods = {5, 8, 10, 14, 20, 25};
        int[] slowPeriods = {30, 40, 50, 80, 100, 150};
        int p1 = fastPeriods[rng.nextInt(fastPeriods.length)];
        int p2 = slowPeriods[rng.nextInt(slowPeriods.length)];
        int s1 = rng.nextInt(0, 3);
        int s2 = Math.max(s1 + 1, rng.nextInt(1, 4));
        double th = switch (bt) {
            case SUPERTREND, KELTNER_CHANNEL, BB_KC_SQUEEZE -> round2(rng.nextDouble(1.4, 2.6));
            case BOLLINGER_BANDS -> "MEAN_REVERSION".equals(arch)
                ? -round2(rng.nextDouble(1.8, 2.5))
                : round2(rng.nextDouble(1.5, 2.3));
            case RSI_FILTER, STOCHASTIC_OSC, WILLIAMS_R -> "MEAN_REVERSION".equals(arch) || "PULLBACK".equals(arch)
                ? rng.nextInt(22, 40)
                : rng.nextInt(52, 65);
            case CCI_OSCILLATOR -> "MEAN_REVERSION".equals(arch)
                ? -rng.nextInt(80, 140)
                : rng.nextInt(70, 130);
            case ZSCORE_EXTREME -> "MEAN_REVERSION".equals(arch)
                ? -round2(rng.nextDouble(1.3, 2.3))
                : round2(rng.nextDouble(1.1, 2.0));
            case ADX_REGIME -> rng.nextInt(18, 28);
            case ATR_EXPANSION -> round2(rng.nextDouble(0.95, 1.25));
            case VOLUME_SURGE -> round2(rng.nextDouble(1.15, 1.65));
            case MOMENTUM_ROC, LINEAR_REG_SLOPE, ENGULFING_PATTERN -> round2(rng.nextDouble(0.20, 0.85));
            case CHOPPINESS_FILTER -> round2(rng.nextDouble(0.28, 0.55));
            default -> 1.0;
        };
        return new RuleGene(bt, p1, p2, s1, s2, th);
    }

    private List<StrategyGenome> breedNextGeneration(
        List<ScoredGenome> ranked,
        GeneratorRequest req,
        List<BlockType> allowedBlocks,
        int popSize
    ) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<StrategyGenome> next = new ArrayList<>(popSize);

        // Elitism + mutated top performers + fresh random immigrants
        int eliteCount = Math.max(2, popSize / 5);
        for (int i = 0; i < Math.min(eliteCount, ranked.size()); i++) {
            next.add(mutateGenome(ranked.get(i).genome(), req, allowedBlocks));
        }

        while (next.size() < popSize) {
            if (ranked.size() >= 4 && !"RANDOM".equalsIgnoreCase(req.searchMode()) && rng.nextDouble() < 0.70) {
                StrategyGenome p1 = tournamentSelect(ranked, rng);
                StrategyGenome p2 = tournamentSelect(ranked, rng);
                next.add(crossoverAndMutate(p1, p2, req, allowedBlocks));
            } else {
                next.add(createRandomGenome(req, allowedBlocks));
            }
        }
        return next;
    }

    private StrategyGenome tournamentSelect(List<ScoredGenome> ranked, ThreadLocalRandom rng) {
        ScoredGenome best = ranked.get(rng.nextInt(ranked.size()));
        for (int i = 0; i < 2; i++) {
            ScoredGenome contender = ranked.get(rng.nextInt(ranked.size()));
            if (contender.fitness > best.fitness) {
                best = contender;
            }
        }
        return best.genome();
    }

    private StrategyGenome crossoverAndMutate(
        StrategyGenome p1,
        StrategyGenome p2,
        GeneratorRequest req,
        List<BlockType> allowedBlocks
    ) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String cleanSym = formatCamelSymbol(req.symbol());
        String cleanArch = formatCamelArchetype(p1.archetype());
        String id = cleanSym + req.timeframe().toUpperCase(Locale.ROOT) + cleanArch + rng.nextInt(10000, 99999);

        List<RuleGene> longRules = new ArrayList<>();
        if (!p1.longRules().isEmpty()) {
            RuleGene first = rng.nextDouble() < 0.25
                ? randomRuleForBlock(pickBlockForArchetype(p1.archetype(), allowedBlocks, 0, rng), p1.archetype(), rng)
                : p1.longRules().getFirst().mutate();
            longRules.add(first);
        } else {
            longRules.add(randomRuleForBlock(pickBlockForArchetype(p1.archetype(), allowedBlocks, 0, rng), p1.archetype(), rng));
        }
        RuleGene secondCandidate = null;
        if (p2.longRules().size() > 1 && rng.nextBoolean()) {
            secondCandidate = p2.longRules().get(1).mutate();
        } else if (p1.longRules().size() > 1 && rng.nextBoolean()) {
            secondCandidate = p1.longRules().get(1).mutate();
        }
        if (secondCandidate != null && secondCandidate.blockType() != longRules.getFirst().blockType()) {
            longRules.add(secondCandidate);
        }

        double sl = round2((p1.slAtrMult() + p2.slAtrMult()) * 0.5 + rng.nextDouble(-0.25, 0.25));
        double tp = round2((p1.tpAtrMult() + p2.tpAtrMult()) * 0.5 + rng.nextDouble(-0.35, 0.35));
        sl = Math.clamp(sl, 1.0, 3.8);
        tp = Math.clamp(tp, sl * 1.15, 7.5);

        List<RuleGene> rulesCopy = List.copyOf(longRules);
        boolean autoSession = "AUTO".equalsIgnoreCase(req.sessionMode());
        boolean higherTf = StrategyGenome.isHigherTimeframe(req.timeframe());
        int startHourEt = req.entryStartHourEt() != null ? req.entryStartHourEt() : 0;
        int endHourEt = req.entryEndHourEt() != null ? req.entryEndHourEt() : 24;
        int daysMask = req.allowedDaysMask() != null ? req.allowedDaysMask() : 31;
        String exitMode = req.exitSessionMode() != null ? req.exitSessionMode() : "CONTINUOUS";
        int cutoffHourEt = req.flatCutoffHourEt() != null ? req.flatCutoffHourEt() : 16;

        if (autoSession) {
            if (rng.nextDouble() < 0.25) {
                int[] structuredMasks = {31, 31, 15, 30, 14};
                daysMask = structuredMasks[rng.nextInt(structuredMasks.length)];
            } else {
                daysMask = rng.nextBoolean() ? p1.resolvedAllowedDaysMask() : p2.resolvedAllowedDaysMask();
            }
            if (higherTf) {
                startHourEt = 0;
                endHourEt = 24;
            } else if (rng.nextDouble() < 0.25) {
                int[][] structuredWindows = {{0, 24}, {9, 16}, {9, 15}, {8, 12}, {3, 8}, {8, 11}};
                int[] win = structuredWindows[rng.nextInt(structuredWindows.length)];
                startHourEt = win[0];
                endHourEt = win[1];
            } else if (rng.nextBoolean()) {
                startHourEt = p1.resolvedEntryStartHourEt();
                endHourEt = p1.resolvedEntryEndHourEt();
            } else {
                startHourEt = p2.resolvedEntryStartHourEt();
                endHourEt = p2.resolvedEntryEndHourEt();
            }
        }
        if (autoSession || "AUTO".equalsIgnoreCase(exitMode)) {
            if (rng.nextDouble() < 0.25) {
                if (higherTf) {
                    String[] htfExits = {"CONTINUOUS", "WEEKEND_FLAT"};
                    exitMode = htfExits[rng.nextInt(htfExits.length)];
                } else {
                    String[] exits = {"CONTINUOUS", "INTRADAY_FLAT", "WEEKEND_FLAT"};
                    exitMode = exits[rng.nextInt(exits.length)];
                }
            } else {
                exitMode = rng.nextBoolean() ? p1.resolvedExitSessionMode() : p2.resolvedExitSessionMode();
            }
        }

        return new StrategyGenome(
            id,
            req.symbol(),
            req.timeframe(),
            p1.archetype(),
            req.direction(),
            rng.nextBoolean() ? p1.entryOrderType() : p2.entryOrderType(),
            rulesCopy,
            rulesCopy,
            rng.nextBoolean() ? p1.stopEntryLookback() : p2.stopEntryLookback(),
            round2(Math.clamp((p1.stopEntryAtrMult() + p2.stopEntryAtrMult()) * 0.5, 0.1, 1.6)),
            rng.nextBoolean() ? p1.stopOrderValidBars() : p2.stopOrderValidBars(),
            p1.atrPeriod(),
            sl,
            tp,
            rng.nextBoolean() ? p1.trailingAtrMult() : p2.trailingAtrMult(),
            rng.nextBoolean() ? p1.maxBarsInTrade() : p2.maxBarsInTrade(),
            startHourEt,
            endHourEt,
            daysMask,
            exitMode,
            cutoffHourEt
        );
    }

    private StrategyGenome mutateGenome(StrategyGenome g, GeneratorRequest req, List<BlockType> allowedBlocks) {
        return crossoverAndMutate(g, createRandomGenome(req, allowedBlocks), req, allowedBlocks);
    }

    private static double computeCompositeScore(
        double pf,
        double isPf,
        double oosPf,
        double sharpe,
        double maxDdPct,
        double retDd,
        double r2,
        int totalTrades,
        int oosTrades,
        double netProfit
    ) {
        if (totalTrades <= 0 || netProfit <= 0) {
            return Math.max(0.0, pf * 8.0);
        }
        double pfScore = Math.min(28.0, Math.max(0.0, (pf - 1.0) * 28.0));
        double oosScore = oosTrades > 0 ? Math.min(24.0, Math.max(0.0, (oosPf - 0.95) * 26.0)) : 10.0;
        double sharpeScore = Math.min(18.0, Math.max(0.0, sharpe * 9.0));
        double r2Score = Math.min(15.0, Math.max(0.0, r2 * 15.0));
        double ddScore = Math.max(0.0, 15.0 - Math.max(0.0, maxDdPct - 5.0) * 0.5);
        return Math.clamp(pfScore + oosScore + sharpeScore + r2Score + ddScore, 1.0, 99.5);
    }

    private static double computeTradeSharpe(List<Double> tradeReturns) {
        if (tradeReturns == null || tradeReturns.size() < 2) {
            return 0.0;
        }
        int n = tradeReturns.size();
        double sum = 0.0;
        for (double r : tradeReturns) {
            sum += r;
        }
        double mean = sum / n;
        double varSum = 0.0;
        for (double r : tradeReturns) {
            double diff = r - mean;
            varSum += diff * diff;
        }
        double std = Math.sqrt(varSum / Math.max(1, n - 1));
        if (std <= 1e-9) {
            return mean > 0 ? 3.0 : 0.0;
        }
        double annualized = (mean / std) * Math.sqrt(Math.min(120.0, Math.max(12.0, n)));
        return Math.clamp(sanitizeMetric(annualized), -5.0, 6.0);
    }

    private static double computeEquityR2(List<Double> curve) {
        if (curve == null || curve.size() < 4) {
            return 0.0;
        }
        int n = curve.size();
        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0, sumY2 = 0.0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = curve.get(i);
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }
        double num = n * sumXY - sumX * sumY;
        double den = (n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY);
        if (den <= 0.0) return 0.0;
        double r = num / Math.sqrt(den);
        return r > 0 ? Math.min(1.0, r * r) : 0.0;
    }

    private static List<Double> sampleCurve(List<Double> curve, int maxPoints) {
        if (curve == null || curve.isEmpty()) return List.of();
        if (curve.size() <= maxPoints) return curve.stream().map(StrategyGeneratorService::round2).toList();
        List<Double> out = new ArrayList<>(maxPoints);
        double step = (double) (curve.size() - 1) / (maxPoints - 1);
        for (int i = 0; i < maxPoints; i++) {
            int idx = Math.min((int) Math.round(i * step), curve.size() - 1);
            out.add(round2(curve.get(idx)));
        }
        return out;
    }

    private static String formatCamelSymbol(String sym) {
        String clean = (sym == null ? "Mgc" : sym).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) {
            return "Mgc";
        }
        if (Character.isDigit(clean.charAt(0))) {
            clean = "s" + clean;
        }
        if (clean.length() == 6) {
            return Character.toUpperCase(clean.charAt(0)) + clean.substring(1, 3)
                + Character.toUpperCase(clean.charAt(3)) + clean.substring(4);
        }
        return Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
    }

    private static String formatCamelArchetype(String arch) {
        return switch (arch.toUpperCase(Locale.ROOT)) {
            case "BREAKOUT" -> "Breakout";
            case "TREND_FOLLOWING" -> "Trend";
            case "MEAN_REVERSION" -> "MeanRev";
            case "MOMENTUM" -> "Momentum";
            case "PULLBACK" -> "Pullback";
            default -> "Quant";
        };
    }

    private static double sanitizeMetric(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        return v;
    }

    private static double round2(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    @Override
    public void close() {
        stopRequested.set(true);
        jobExecutor.shutdownNow();
    }

    private static final class StrategyCatalogFixedQtyWrapper implements Strategy {
        private final Strategy delegate;
        private final double quantityUnits;

        StrategyCatalogFixedQtyWrapper(Strategy delegate, double quantityUnits) {
            this.delegate = delegate;
            this.quantityUnits = quantityUnits;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public void reconcileInstrument(String brokerSymbol) {
            delegate.reconcileInstrument(brokerSymbol);
        }

        @Override
        public void onTick(double bid, double ask, long volume) {
            delegate.onTick(bid, ask, volume);
        }

        @Override
        public void onBar(Bar bar) {
            delegate.onBar(bar);
        }

        @Override
        public List<com.martinfou.trading.core.Order> getPendingOrders() {
            return delegate.getPendingOrders().stream()
                .map(o -> o.rescaleQuantity(quantityUnits))
                .toList();
        }

        @Override
        public void reset() {
            delegate.reset();
        }
    }
}
