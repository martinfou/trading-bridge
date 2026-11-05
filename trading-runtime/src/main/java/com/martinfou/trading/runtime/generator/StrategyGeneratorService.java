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
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
        Double slippagePct
    ) {}

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
        String javaSourceCode
    ) {}

    private final Path storageDir;
    private final Path databankFile;
    private final Path promotedFile;
    private final Path sqImportedSourceDir;
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
        this(
            RuntimeDataPaths.defaultDataDirectory().resolve("generator"),
            resolveSqImportedDir()
        );
    }

    private static Path resolveSqImportedDir() {
        Path repoRoot = RuntimeDataPaths.scriptsDirectory().getParent();
        if (repoRoot != null) {
            return repoRoot.resolve("trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported");
        }
        return Path.of("trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported");
    }

    public StrategyGeneratorService(Path storageDir, Path sqImportedSourceDir) {
        this.storageDir = storageDir;
        this.databankFile = storageDir.resolve("databank.json");
        this.promotedFile = storageDir.resolve("promoted.json");
        this.sqImportedSourceDir = sqImportedSourceDir;
        loadPersistedState();
    }

    private void loadPersistedState() {
        try {
            Files.createDirectories(storageDir);
            if (Files.isRegularFile(databankFile)) {
                List<DatabankCandidate> loaded = MAPPER.readValue(databankFile.toFile(), new TypeReference<>() {});
                if (loaded != null) {
                    databank.addAll(loaded);
                }
            }
            if (Files.isRegularFile(promotedFile)) {
                List<DatabankCandidate> promoted = MAPPER.readValue(promotedFile.toFile(), new TypeReference<>() {});
                if (promoted != null) {
                    for (DatabankCandidate c : promoted) {
                        promotedCatalog.put(c.id(), c);
                        registerInCatalog(c);
                    }
                }
            }
            for (DatabankCandidate c : databank) {
                if (c.promoted()) {
                    promotedCatalog.put(c.id(), c);
                }
                registerInCatalog(c);
            }
        } catch (Exception e) {
            log.warn("Failed to load persisted StrategyGenerator databank: {}", e.getMessage());
        }
    }

    private synchronized void persistDatabank() {
        try {
            Files.createDirectories(storageDir);
            MAPPER.writeValue(databankFile.toFile(), new ArrayList<>(databank));
            for (DatabankCandidate c : databank) {
                if (c.promoted()) {
                    promotedCatalog.put(c.id(), c);
                }
            }
            MAPPER.writeValue(promotedFile.toFile(), new ArrayList<>(promotedCatalog.values()));
        } catch (Exception e) {
            log.warn("Failed to persist generator databank: {}", e.getMessage());
        }
    }

    private void registerInCatalog(DatabankCandidate c) {
        if (c == null || c.genome() == null) {
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
            StrategyCatalog.Family.SQ_IMPORTED,
            c.symbol(),
            c.archetype(),
            c.indicators(),
            desc,
            c.archetype(),
            List.of(c.timeframe()),
            sym -> genome.toExecutableStrategy(sym)
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
        persistDatabank();
    }

    public void clearDatabank() {
        databank.clear();
        persistDatabank();
    }

    public DatabankCandidate promoteToCatalog(String id) throws IOException {
        DatabankCandidate existing = getCandidate(id)
            .orElseThrow(() -> new IllegalArgumentException("Candidate not found in databank: " + id));

        String javaPathStr = existing.javaFilePath();
        try {
            Files.createDirectories(sqImportedSourceDir);
            Path targetJava = sqImportedSourceDir.resolve(existing.id() + ".java");
            Files.writeString(targetJava, existing.javaSourceCode());
            javaPathStr = targetJava.toString();
            log.info("Wrote generated strategy source to {}", targetJava);
            Path catalogJava = sqImportedSourceDir.resolve("SqImportedStrategyCatalog.java");
            if (Files.isRegularFile(catalogJava)) {
                String catalogContent = Files.readString(catalogJava);
                String regLine = "register(\"" + existing.id() + "\", " + existing.id() + "::new);";
                if (!catalogContent.contains(regLine)) {
                    int idx = catalogContent.indexOf("    private SqImportedStrategyCatalog()");
                    if (idx > 0) {
                        int closeBrace = catalogContent.lastIndexOf("    }", idx);
                        if (closeBrace > 0) {
                            String updatedCatalog = catalogContent.substring(0, closeBrace)
                                + "        " + regLine + "\n"
                                + catalogContent.substring(closeBrace);
                            Files.writeString(catalogJava, updatedCatalog);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not write Java file for {} (registering in runtime catalog anyway): {}", id, e.getMessage());
        }

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
            javaPathStr,
            existing.longSignalSummary(),
            existing.shortSignalSummary(),
            existing.entrySummary(),
            existing.exitSummary(),
            existing.indicators(),
            existing.equityCurve(),
            existing.oosStartIndex(),
            existing.genome(),
            existing.javaSourceCode()
        );

        for (int i = 0; i < databank.size(); i++) {
            if (databank.get(i).id().equals(id)) {
                databank.set(i, updated);
                break;
            }
        }
        registerInCatalog(updated);
        persistDatabank();
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
            : List.of("DONCHIAN_BREAKOUT", "MA_CROSSOVER", "SUPERTREND", "BOLLINGER_BANDS", "RSI_FILTER", "ADX_REGIME", "ATR_EXPANSION", "DAILY_HIGH_LOW");
        int minTrades = rawReq.minTrades() != null ? Math.max(1, rawReq.minTrades()) : 15;
        double minPf = rawReq.minProfitFactor() != null ? rawReq.minProfitFactor() : 1.15;
        double minOosPf = rawReq.minOosProfitFactor() != null ? rawReq.minOosProfitFactor() : 1.05;
        double minSharpe = rawReq.minSharpe() != null ? rawReq.minSharpe() : 0.5;
        double maxDd = rawReq.maxDrawdownPct() != null ? rawReq.maxDrawdownPct() : 25.0;
        double capital = rawReq.initialCapital() != null && rawReq.initialCapital() > 0 ? rawReq.initialCapital() : 10_000.0;
        double lotSize = rawReq.lotSize() != null && rawReq.lotSize() > 0 ? rawReq.lotSize() : 1.0;
        double commission = rawReq.commissionPerTrade() != null && rawReq.commissionPerTrade() >= 0 ? rawReq.commissionPerTrade() : 0.62;
        double slippage = rawReq.slippagePct() != null && rawReq.slippagePct() >= 0 ? rawReq.slippagePct() : 0.00005;

        GeneratorRequest req = new GeneratorRequest(
            symbol, tf, yearSpec, oosSplitPct, searchMode, archetype, direction, entryOrderType,
            popSize, gens, blocks, minTrades, minPf, minOosPf, minSharpe, maxDd,
            capital, lotSize, commission, slippage
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

    private void executeMiningJob(String jobId, GeneratorRequest req) {
        try {
            List<Bar> bars = loadOrGenerateBars(req.symbol(), req.timeframe(), req.yearSpec());
            if (bars.size() < 150) {
                throw new IllegalStateException("Not enough historical bars (" + bars.size() + ") for " + req.symbol() + " " + req.timeframe());
            }

            this.totalBarsLoaded = bars.size();
            int splitIdx = Math.clamp((int) Math.round(bars.size() * (100 - req.oosSplitPct()) / 100.0), 80, bars.size() - 40);
            List<Bar> isBars = bars.subList(0, splitIdx);
            List<Bar> oosBars = bars.subList(Math.max(0, splitIdx - 60), bars.size());
            this.isBarsCount = isBars.size();
            this.oosBarsCount = bars.size() - splitIdx;

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
                        "Evolving Generation %d / %d on %s %s (%,d bars: %,d IS / %,d OOS)...",
                        gen, gens, req.symbol(), req.timeframe(), bars.size(), isBarsCount, oosBarsCount
                    );

                    final int genNum = gen;
                    List<CompletableFuture<ScoredGenome>> futures = new ArrayList<>(population.size());
                    for (StrategyGenome genome : population) {
                        futures.add(CompletableFuture.supplyAsync(
                            () -> evaluateCandidate(genome, req, bars, isBars, oosBars, splitIdx, genNum),
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
                                databank.add(sg.candidate);
                                registerInCatalog(sg.candidate);
                                passedCount.incrementAndGet();
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
        List<Bar> isBars,
        List<Bar> oosBars,
        int splitIdx,
        int generation
    ) {
        if (stopRequested.get()) {
            return null;
        }
        try {
            double quantityUnits = LotSizing.resolveQuantityUnits(req.lotSize(), req.symbol());

            // 1. In-Sample Backtest
            Strategy isStrat = new StrategyCatalogFixedQtyWrapper(genome.toExecutableStrategy(req.symbol()), quantityUnits);
            BacktestResult isResult = new BacktestEngine(isStrat, isBars, req.initialCapital())
                .withCommissionFixed(req.commissionPerTrade())
                .withSlippagePct(req.slippagePct())
                .run();

            evaluatedCount.incrementAndGet();

            int isTrades = isResult.totalTrades();
            double isPf = sanitizeMetric(isResult.profitFactor());
            double isSharpe = sanitizeMetric(isResult.sharpeRatio());
            double isNetProfit = isResult.finalEquity() - req.initialCapital();

            if (isTrades < Math.max(3, req.minTrades() / 2)) {
                return new ScoredGenome(genome, isTrades * 0.5, null);
            }

            // 2. Out-of-Sample Backtest
            Strategy oosStrat = new StrategyCatalogFixedQtyWrapper(genome.toExecutableStrategy(req.symbol()), quantityUnits);
            BacktestResult oosResult = new BacktestEngine(oosStrat, oosBars, req.initialCapital())
                .withCommissionFixed(req.commissionPerTrade())
                .withSlippagePct(req.slippagePct())
                .run();

            int oosTrades = oosResult.totalTrades();
            int totalTrades = isTrades + oosTrades;
            double oosPf = sanitizeMetric(oosResult.profitFactor());
            double oosSharpe = sanitizeMetric(oosResult.sharpeRatio());
            double oosNetProfit = oosResult.finalEquity() - req.initialCapital();

            // 3. Full Period Backtest for unified equity curve & Monte Carlo
            Strategy fullStrat = new StrategyCatalogFixedQtyWrapper(genome.toExecutableStrategy(req.symbol()), quantityUnits);
            BacktestResult fullResult = new BacktestEngine(fullStrat, fullBars, req.initialCapital())
                .withCommissionFixed(req.commissionPerTrade())
                .withSlippagePct(req.slippagePct())
                .run();

            double netProfit = fullResult.finalEquity() - req.initialCapital();
            double pf = sanitizeMetric(fullResult.profitFactor());
            double sharpe = sanitizeMetric(fullResult.sharpeRatio());
            double maxDdPct = sanitizeMetric(fullResult.maxDrawdownPct());
            double totalReturnPct = sanitizeMetric(fullResult.totalReturnPct());
            double winRatePct = sanitizeMetric(fullResult.winRatePct());
            double retDd = maxDdPct > 0.25 ? totalReturnPct / maxDdPct : (totalReturnPct > 0 ? totalReturnPct * 2.0 : 0.0);
            double r2 = computeEquityR2(fullResult.equityCurve());

            double compositeScore = computeCompositeScore(
                pf, isPf, oosPf, sharpe, maxDdPct, retDd, r2, fullResult.totalTrades(), oosTrades, netProfit
            );

            // Check Acceptance Gate
            boolean passes = fullResult.totalTrades() >= req.minTrades()
                && pf >= req.minProfitFactor()
                && (oosTrades == 0 || oosPf >= req.minOosProfitFactor())
                && sharpe >= req.minSharpe()
                && maxDdPct <= req.maxDrawdownPct()
                && netProfit > 0;

            if (!passes) {
                return new ScoredGenome(genome, compositeScore * 0.65, null);
            }

            // Monte Carlo simulation (80 runs for speed)
            double mcVar95 = 0.0;
            double mcLossProb = 0.0;
            try {
                if (fullResult.trades() != null && fullResult.trades().size() >= 6) {
                    MonteCarloSimulation.Result mc = new MonteCarloSimulation(fullResult, 80, 3).run();
                    mcVar95 = sanitizeMetric(mc.var95());
                    mcLossProb = sanitizeMetric(mc.probabilityOfLoss());
                }
            } catch (Exception ignored) {}

            List<Double> sampledCurve = sampleCurve(fullResult.equityCurve(), 140);
            int sampledOosIdx = (int) Math.round((splitIdx / (double) fullBars.size()) * Math.max(1, sampledCurve.size() - 1));

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
                "trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported/" + genome.id() + ".java",
                genome.longSignalSummary(),
                genome.shortSignalSummary(),
                genome.entrySummary(),
                genome.exitSummary(),
                genome.indicatorsUsed(),
                sampledCurve,
                sampledOosIdx,
                genome,
                javaCode
            );

            return new ScoredGenome(genome, compositeScore, candidate);
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
        String id = "Sq" + cleanSym + req.timeframe().toUpperCase(Locale.ROOT) + cleanArch + serial;

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

        return new StrategyGenome(
            id, req.symbol(), req.timeframe(), arch, req.direction(), orderType,
            longRules, shortRules, stopLookback, stopAtrMult, validBars,
            atrPeriod, slAtr, tpAtr, trailAtr, maxBars
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
        String id = "Sq" + cleanSym + req.timeframe().toUpperCase(Locale.ROOT) + cleanArch + rng.nextInt(10000, 99999);

        List<RuleGene> longRules = new ArrayList<>();
        if (!p1.longRules().isEmpty()) {
            longRules.add(p1.longRules().getFirst().mutate());
        }
        if (p2.longRules().size() > 1 && rng.nextBoolean()) {
            longRules.add(p2.longRules().get(1).mutate());
        }

        double sl = round2((p1.slAtrMult() + p2.slAtrMult()) * 0.5 + rng.nextDouble(-0.25, 0.25));
        double tp = round2((p1.tpAtrMult() + p2.tpAtrMult()) * 0.5 + rng.nextDouble(-0.35, 0.35));
        sl = Math.clamp(sl, 1.0, 3.8);
        tp = Math.clamp(tp, sl * 1.15, 7.5);

        return new StrategyGenome(
            id,
            req.symbol(),
            req.timeframe(),
            p1.archetype(),
            req.direction(),
            rng.nextBoolean() ? p1.entryOrderType() : p2.entryOrderType(),
            longRules,
            longRules,
            rng.nextBoolean() ? p1.stopEntryLookback() : p2.stopEntryLookback(),
            round2(Math.clamp((p1.stopEntryAtrMult() + p2.stopEntryAtrMult()) * 0.5, 0.1, 1.6)),
            rng.nextBoolean() ? p1.stopOrderValidBars() : p2.stopOrderValidBars(),
            p1.atrPeriod(),
            sl,
            tp,
            rng.nextBoolean() ? p1.trailingAtrMult() : p2.trailingAtrMult(),
            rng.nextBoolean() ? p1.maxBarsInTrade() : p2.maxBarsInTrade()
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
        String clean = sym.replace("_", "").replace("/", "").toLowerCase(Locale.ROOT);
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
