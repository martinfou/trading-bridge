package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.backtest.WalkForwardOptimizer;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Batch Strategy Generator — StrategyQuant-style pipeline.
 *
 * <p>Generates, quick-tests, ranks, validates, and exports hundreds of
 * trading strategies in a single command. Uses Virtual Threads (Java 21)
 * for maximum throughput.</p>
 *
 * <h3>Workflow (StrategyQuant-inspired)</h3>
 * <ol>
 *   <li><b>Generate</b> — Create N strategy chromosomes per type</li>
 *   <li><b>Quick Screen</b> — Backtest all on 100 bars for initial ranking</li>
 *   <li><b>Rank</b> — Sort by composite score (Sharpe + PF + WinRate + DD)</li>
 *   <li><b>Validate</b> — Top 10% get Walk-Forward + Monte Carlo validation</li>
 *   <li><b>Export</b> — Top 20 as compilable Java files + ranking report</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   java -cp ... com.martinfou.trading.genetics.BatchStrategyRunner \
 *        --count 500 --types all --bars 250 --capital 100000 \
 *        --output ./batch-results/ --threads 8
 * }</pre>
 */
public final class BatchStrategyRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchStrategyRunner.class);

    private static final int QUICK_SCREEN_BARS = 100;
    private static final double VALIDATE_TOP_FRACTION = 0.10;
    private static final int EXPORT_TOP_N = 20;
    private static final int WFO_IS_DAYS = 180;
    private static final int WFO_OOS_DAYS = 60;
    private static final int MONTE_CARLO_RUNS = 500;
    private static final int SYNTHETIC_BAR_SECS = 86400;

    // Generation batches for iterative mode
    private static final int BATCH_SIZE = 50;

    // ===============================================================
    //  Selection Criteria
    // ===============================================================

    /**
     * User-defined criteria that a strategy must meet to be considered "good".
     * When targetCount > 0, the runner keeps generating until it finds enough
     * strategies that pass these criteria (up to maxAttempts).
     */
    public record SelectionCriteria(
        double minSharpe,
        double minProfitFactor,
        double maxDrawdown,
        double minWinRate,
        int targetCount,
        int maxAttempts
    ) {
        public static final double DEFAULT_MIN_SHARPE = 1.0;
        public static final double DEFAULT_MIN_PF = 1.5;
        public static final double DEFAULT_MAX_DD = 25.0;
        public static final double DEFAULT_MIN_WIN_RATE = 40.0;
        public static final int DEFAULT_TARGET_COUNT = 0; // 0 = disabled (use fixed count)
        public static final int DEFAULT_MAX_ATTEMPTS = 10_000;

        public static SelectionCriteria disabled() {
            return new SelectionCriteria(
                DEFAULT_MIN_SHARPE, DEFAULT_MIN_PF, DEFAULT_MAX_DD,
                DEFAULT_MIN_WIN_RATE, 0, DEFAULT_MAX_ATTEMPTS);
        }

        public boolean isEnabled() {
            return targetCount > 0;
        }
    }

    // ===============================================================
    //  Entry point
    // ===============================================================

    public static void main(String[] args) {
        var config = parseArgs(args);

        log.info("Batch Strategy Generator — StrategyQuant Style");
        log.info("Count: {} | Types: {} | Bars: {} | Capital: ${}",
            config.count, config.types, config.bars, config.capital);
        if (config.selectionCriteria().isEnabled()) {
            var sc = config.selectionCriteria();
            log.info("Selection criteria: Sharpe≥{} PF≥{} DD≤{} WinRate≥{} Target={} MaxAttempts={}",
                format2(sc.minSharpe()), format2(sc.minProfitFactor()),
                format2(sc.maxDrawdown()), format2(sc.minWinRate()),
                sc.targetCount(), sc.maxAttempts());
        }
        log.info("Output: {} | Threads: {}", config.outputDir, config.threads);

        Instant startTime = Instant.now();
        try {
            run(config);
        } catch (Exception e) {
            log.error("Batch run failed", e);
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }

        Duration elapsed = Duration.between(startTime, Instant.now());
        log.info("Batch complete in {}m {}s", elapsed.toMinutes(), elapsed.toSeconds() % 60);
    }

    static void run(Config config) throws Exception {
        // Phase 0: Resolve types
        List<StrategyBuilder.StrategyType> types = resolveTypes(config.types);
        log.info("Strategy types: {}", types.stream().map(Enum::name).collect(Collectors.joining(",")));

        // Decide mode: fixed-count generation vs. iterative selection
        List<StrategyBundle> allStrategies;
        if (config.selectionCriteria().isEnabled()) {
            allStrategies = generateUntilCriteriaMet(config, types);
        } else {
            allStrategies = generateFixedCount(config, types);
        }

        if (allStrategies.isEmpty()) {
            log.warn("No strategies generated — producing empty report");
            exportResults(List.of(), config.outputDir);
            return;
        }

        // Phase 3: Rank by composite score
        log.info("PHASE 3: Ranking");
        List<StrategyBundle> ranked = rankStrategies(allStrategies);

        log.info("Top 5 strategies:");
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            var b = ranked.get(i);
            log.info("  #{}. {} | Sharpe={} | PF={} | Score={} | {}",
                i + 1, b.name, format2(b.quickSharpe), format2(b.quickPf),
                format2(b.compositeScore), colorLabel(b.compositeScore));
        }

        // Phase 4: Validate top 10%
        int validateCount = Math.max(1, (int) (ranked.size() * VALIDATE_TOP_FRACTION));
        log.info("PHASE 4: Validating top {} strategies ({}%)", validateCount, (int) (VALIDATE_TOP_FRACTION * 100));
        List<Bar> barsToUse = config.dataPath != null && !config.dataPath.isEmpty()
            ? loadCSVData(config.dataPath)
            : null;
        if (barsToUse == null) {
            barsToUse = generateBars(config.bars);
        }
        final List<Bar> fullBars = barsToUse;
        AtomicInteger validated = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < validateCount && i < ranked.size(); i++) {
                var bundle = ranked.get(i);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        validateStrategy(bundle, fullBars, config.capital);
                    } catch (Exception e) {
                        log.warn("Validation failed for #{}: {}", bundle.rank, e.getMessage());
                    }
                    int done = validated.incrementAndGet();
                    log.info("Validated {}/{}", done, validateCount);
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // Phase 5: Export
        log.info("PHASE 5: Exporting results to {}", config.outputDir);
        exportResults(ranked, config.outputDir);

        // Final summary
        long promising = ranked.stream().filter(b -> b.compositeScore >= 70).count();
        long medium = ranked.stream().filter(b -> b.compositeScore >= 40 && b.compositeScore < 70).count();
        long weak = ranked.stream().filter(b -> b.compositeScore < 40).count();
        int goodCount = (int) ranked.stream().filter(b -> passesCriteria(b, config.selectionCriteria())).count();
        log.info("SUMMARY: {} total | Good(met criteria) {} | Promising {} | Medium {} | Weak {} | Validated {} | Exported {}",
            ranked.size(), goodCount, promising, medium, weak, validateCount, Math.min(EXPORT_TOP_N, ranked.size()));
    }

    /**
     * Fixed-count generation: create exactly N strategies, screen all, then rank.
     */
    private static List<StrategyBundle> generateFixedCount(Config config, List<StrategyBuilder.StrategyType> types) throws Exception {
        log.info("PHASE 1: Generating {} strategies (fixed count)", config.count);
        int perType = (int) Math.ceil((double) config.count / types.size());
        List<StrategyBundle> allStrategies = new CopyOnWriteArrayList<>();
        AtomicInteger totalGenerated = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (var type : types) {
                futures.add(CompletableFuture.runAsync(() -> {
                    List<StrategyBundle> generated = generateForType(type, perType, totalGenerated, config.count);
                    allStrategies.addAll(generated);
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        while (allStrategies.size() > config.count) {
            allStrategies.remove(allStrategies.size() - 1);
        }
        log.info("Generated {} strategies total", allStrategies.size());

        // Phase 2: Quick Screen — use real data when available
        log.info("PHASE 2: Quick screening on {} bars", QUICK_SCREEN_BARS);
        List<Bar> screenBars = loadScreenBars(config.dataPath, QUICK_SCREEN_BARS);
        screenAllParallel(allStrategies, screenBars);

        return allStrategies;
    }

    /**
     * Iterative generation: keep generating batches until we find targetCount
     * strategies that meet all selection criteria, or we hit maxAttempts.
     */
    private static List<StrategyBundle> generateUntilCriteriaMet(Config config, List<StrategyBuilder.StrategyType> types) throws Exception {
        var sc = config.selectionCriteria();
        log.info("PHASE 1: Generating strategies until {} meet criteria (Sharpe≥{} PF≥{} DD≤{} WinRate≥{})",
            sc.targetCount(), format2(sc.minSharpe()), format2(sc.minProfitFactor()),
            format2(sc.maxDrawdown()), format2(sc.minWinRate()));
        log.info("         Max attempts: {} | Batch size: {}", sc.maxAttempts(), BATCH_SIZE);

        List<StrategyBundle> goodStrategies = new ArrayList<>();
        List<Bar> screenBars = loadScreenBars(config.dataPath, QUICK_SCREEN_BARS);
        AtomicInteger totalAttempts = new AtomicInteger(0);
        int typeCount = types.size();
        var rng = ThreadLocalRandom.current();

        while (totalAttempts.get() < sc.maxAttempts() && goodStrategies.size() < sc.targetCount()) {
            int batchPerType = Math.max(1, BATCH_SIZE / typeCount);
            int remaining = sc.maxAttempts() - totalAttempts.get();
            int batchActual = Math.min(BATCH_SIZE, remaining);
            batchPerType = Math.max(1, batchActual / typeCount);

            // Generate a batch
            List<StrategyBundle> batch = new CopyOnWriteArrayList<>();
            AtomicInteger batchGen = new AtomicInteger(0);
            int finalBatchPerType = batchPerType;

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (var type : types) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        List<StrategyBundle> generated = generateForType(type, finalBatchPerType, batchGen, batchActual);
                        batch.addAll(generated);
                    }, executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            int batchSize = batch.size();
            totalAttempts.addAndGet(batchSize);

            // Quick screen the batch in parallel
            screenAllParallel(batch, screenBars);

            // Evaluate which one pass criteria
            for (var b : batch) {
                if (b.quickResult != null && passesCriteria(b, sc)) {
                    b.passedSelection = true;
                    goodStrategies.add(b);
                    log.info("Found {}/{} good strategies (Sharpe≥{} PF≥{} DD≤{}%) — continuing...",
                        goodStrategies.size(), sc.targetCount(),
                        format2(sc.minSharpe()), format2(sc.minProfitFactor()),
                        format2(sc.maxDrawdown()));

                    if (goodStrategies.size() >= sc.targetCount()) {
                        break;
                    }
                }
            }

            int attempted = totalAttempts.get();
            log.info("Batch done: attempted={}/{} found={}/{} good so far",
                attempted, sc.maxAttempts(), goodStrategies.size(), sc.targetCount());
        }

        int attempted = totalAttempts.get();
        if (goodStrategies.size() >= sc.targetCount()) {
            log.info("Found {}/{} good strategies — stopping early!",
                goodStrategies.size(), sc.targetCount());
        } else {
            log.warn("Only found {}/{} good strategies after {} attempts (max {}). " +
                    "Consider relaxing criteria.",
                goodStrategies.size(), sc.targetCount(), attempted, sc.maxAttempts());
        }

        log.info("Generated {} strategies total in {} attempts", goodStrategies.size(), attempted);
        return goodStrategies;
    }

    /**
     * Returns true if a strategy's quick-screen results meet all selection criteria.
     */
    private static boolean passesCriteria(StrategyBundle bundle, SelectionCriteria sc) {
        if (bundle.quickResult == null) return false;
        return bundle.quickResult.sharpeRatio() >= sc.minSharpe()
            && bundle.quickResult.profitFactor() >= sc.minProfitFactor()
            && bundle.quickResult.maxDrawdownPct() <= sc.maxDrawdown()
            && bundle.quickResult.winRatePct() >= sc.minWinRate();
    }

    /**
     * Quick-screens all strategies in parallel using Virtual Threads.
     */
    private static void screenAllParallel(List<StrategyBundle> strategies, List<Bar> bars) throws Exception {
        AtomicInteger screened = new AtomicInteger(0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (var bundle : strategies) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        bundle.quickResult = quickBacktest(bundle.chromosome, bars);
                    } catch (Exception e) {
                        log.warn("Quick screen failed: {}", e.getMessage());
                        bundle.quickResult = null;
                    }
                    int done = screened.incrementAndGet();
                    if (done % 50 == 0 || done == strategies.size()) {
                        log.info("Screened {}/{} ({}%)", done, strategies.size(),
                            done * 100 / strategies.size());
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    // ===============================================================
    //  Strategy Generation
    // ===============================================================

    private static List<StrategyBundle> generateForType(
            StrategyBuilder.StrategyType type, int count, AtomicInteger totalGenerated, int maxTotal) {
        List<StrategyBundle> bundles = new ArrayList<>();
        var rng = ThreadLocalRandom.current();

        for (int i = 0; i < count; i++) {
            if (totalGenerated.get() >= maxTotal) break;

            int smaP = clampPeriod(10 + rng.nextInt(80), 3, 200);
            int emaF = clampPeriod(5 + rng.nextInt(25), 2, 50);
            int emaS = clampPeriod(emaF + 5 + rng.nextInt(30), 7, 100);
            int rsiP = clampPeriod(7 + rng.nextInt(14), 3, 30);
            int atrP = clampPeriod(7 + rng.nextInt(14), 3, 30);
            int adxP = clampPeriod(7 + rng.nextInt(14), 3, 30);
            int sl = rng.nextInt(10, 200);
            int tp = sl + rng.nextInt(10, 300);

            Chromosome chromo = createDiverseChromosome(type, smaP, emaF, emaS, rsiP, atrP, adxP, sl, tp, rng);
            String name = type.name().charAt(0) + type.name().substring(1).toLowerCase()
                + "_" + (i + 1) + "_" + chromo.entryGenes().get(0).indicatorType()
                + chromo.entryGenes().get(0).period();

            bundles.add(new StrategyBundle(name, type, chromo));
            totalGenerated.incrementAndGet();
        }
        return bundles;
    }

    private static Chromosome createDiverseChromosome(
            StrategyBuilder.StrategyType type, int sma, int emaF, int emaS,
            int rsiP, int atrP, int adxP, int sl, int tp, ThreadLocalRandom rng) {
        return switch (type) {
            case TREND_FOLLOWING -> {
                int v = rng.nextInt(4);
                List<Gene> e, x;
                switch (v) {
                    case 0 -> { e = List.of(eg(Gene.IndicatorType.SMA, sma), eg(Gene.IndicatorType.SMA, sma + 30));
                                x = List.of(eg(Gene.IndicatorType.SMA, sma + 10), eg(Gene.IndicatorType.SMA, sma + 30)); }
                    case 1 -> { e = List.of(eg(Gene.IndicatorType.EMA, emaF), eg(Gene.IndicatorType.ADX, adxP));
                                x = List.of(eg(Gene.IndicatorType.EMA, emaS)); }
                    case 2 -> { e = List.of(eg(Gene.IndicatorType.EMA, emaF), eg(Gene.IndicatorType.EMA, emaS));
                                x = List.of(eg(Gene.IndicatorType.SMA, sma)); }
                    default -> { e = List.of(eg(Gene.IndicatorType.SMA, sma), eg(Gene.IndicatorType.ADX, adxP));
                                 x = List.of(eg(Gene.IndicatorType.SMA, sma / 2)); }
                }
                yield new Chromosome(e, x, sl, tp);
            }
            case MEAN_REVERSION -> {
                int v = rng.nextInt(3);
                List<Gene> e, x;
                switch (v) {
                    case 0 -> { e = List.of(eg(Gene.IndicatorType.RSI, rsiP));
                                x = List.of(eg(Gene.IndicatorType.RSI, rsiP)); }
                    case 1 -> { e = List.of(eg(Gene.IndicatorType.RSI, rsiP), eg(Gene.IndicatorType.SMA, sma));
                                x = List.of(eg(Gene.IndicatorType.RSI, rsiP)); }
                    default -> { e = List.of(eg(Gene.IndicatorType.RSI, rsiP), eg(Gene.IndicatorType.EMA, emaF));
                                 x = List.of(eg(Gene.IndicatorType.SMA, sma)); }
                }
                yield new Chromosome(e, x, sl / 2, tp / 2);
            }
            case BREAKOUT -> {
                int v = rng.nextInt(3);
                List<Gene> e, x;
                switch (v) {
                    case 0 -> { e = List.of(eg(Gene.IndicatorType.ATR, atrP), eg(Gene.IndicatorType.ADX, adxP));
                                x = List.of(eg(Gene.IndicatorType.EMA, emaF)); }
                    case 1 -> { e = List.of(eg(Gene.IndicatorType.ATR, atrP), eg(Gene.IndicatorType.EMA, emaF));
                                x = List.of(eg(Gene.IndicatorType.SMA, sma)); }
                    default -> { e = List.of(eg(Gene.IndicatorType.ADX, adxP), eg(Gene.IndicatorType.EMA, emaF));
                                 x = List.of(eg(Gene.IndicatorType.ATR, atrP)); }
                }
                yield new Chromosome(e, x, sl * 2, tp * 2);
            }
            case MOMENTUM -> {
                int v = rng.nextInt(3);
                List<Gene> e, x;
                switch (v) {
                    case 0 -> { e = List.of(eg(Gene.IndicatorType.EMA, emaF), eg(Gene.IndicatorType.RSI, rsiP));
                                x = List.of(eg(Gene.IndicatorType.EMA, emaS)); }
                    case 1 -> { e = List.of(eg(Gene.IndicatorType.EMA, emaF), eg(Gene.IndicatorType.EMA, emaS));
                                x = List.of(eg(Gene.IndicatorType.RSI, rsiP)); }
                    default -> { e = List.of(eg(Gene.IndicatorType.RSI, rsiP), eg(Gene.IndicatorType.ADX, adxP));
                                 x = List.of(eg(Gene.IndicatorType.EMA, emaF)); }
                }
                yield new Chromosome(e, x, sl, tp);
            }
        };
    }

    private static Gene eg(Gene.IndicatorType t, int p) {
        return new Gene(t, p, Gene.Field.CLOSE);
    }

    // ===============================================================
    //  Quick Screening
    // ===============================================================

    private static BacktestResult quickBacktest(Chromosome chromosome, List<Bar> bars) {
        Strategy strategy = new StrategyTemplate(chromosome);
        BacktestEngine engine = new BacktestEngine(strategy, bars, 100_000.0);
        engine.withCommissionPct(0.0007);
        return engine.run();
    }

    // ===============================================================
    //  Ranking
    // ===============================================================

    private static List<StrategyBundle> rankStrategies(List<StrategyBundle> bundles) {
        for (var b : bundles) {
            if (b.quickResult != null) {
                double sharpe = normalizeSharpe(b.quickResult.sharpeRatio());
                double pf = normalizeProfitFactor(b.quickResult.profitFactor());
                double winRate = normalizeWinRate(b.quickResult.winRatePct());
                double ddPenalty = drawdownPenalty(b.quickResult.maxDrawdownPct());
                double score = Math.clamp(sharpe * 0.40 + pf * 0.25 + winRate * 0.20 + ddPenalty * 0.15, 0, 100);

                b.compositeScore = score;
                b.quickSharpe = b.quickResult.sharpeRatio();
                b.quickPf = b.quickResult.profitFactor();
                b.quickWinRate = b.quickResult.winRatePct();
                b.quickMaxDd = b.quickResult.maxDrawdownPct();
                b.quickReturn = b.quickResult.totalReturnPct();
                b.quickTrades = b.quickResult.totalTrades();
            }
        }

        bundles.sort((a, b) -> Double.compare(b.compositeScore, a.compositeScore));
        for (int i = 0; i < bundles.size(); i++) {
            bundles.get(i).rank = i + 1;
        }
        return bundles;
    }

    private static double normalizeSharpe(double s) {
        if (s <= 0) return 0;
        if (s >= 3.0) return 40;
        if (s >= 2.0) return 32 + (s - 2.0) * 8;
        if (s >= 1.0) return 20 + (s - 1.0) * 12;
        return s * 20;
    }

    private static double normalizeProfitFactor(double pf) {
        if (pf <= 0) return 0;
        if (pf >= 3.0) return 25;
        if (pf >= 2.0) return 18 + (pf - 2.0) * 7;
        if (pf >= 1.5) return 12 + (pf - 1.5) * 12;
        if (pf >= 1.0) return 5 + (pf - 1.0) * 14;
        return 0;
    }

    private static double normalizeWinRate(double wr) {
        if (wr <= 0) return 0;
        if (wr >= 100) return 20;
        return wr / 100.0 * 20;
    }

    private static double drawdownPenalty(double dd) {
        if (dd <= 0) return 15;
        if (dd >= 100) return 0;
        return Math.max(0, 15 - dd * 0.3);
    }

    // ===============================================================
    //  Validation
    // ===============================================================

    private static void validateStrategy(StrategyBundle bundle, List<Bar> fullBars, double capital) {
        Strategy strategy = new StrategyTemplate(bundle.chromosome);
        BacktestEngine engine = new BacktestEngine(strategy, fullBars, capital);
        engine.withCommissionPct(0.0007);
        bundle.fullResult = engine.run();

        try {
            WalkForwardOptimizer wfo = new WalkForwardOptimizer(
                (isBars, oosBars) -> {
                    Strategy s = new StrategyTemplate(bundle.chromosome);
                    BacktestEngine be = new BacktestEngine(s, oosBars, capital);
                    be.withCommissionPct(0.0007);
                    return be.run();
                },
                fullBars, WFO_IS_DAYS, WFO_OOS_DAYS
            );
            bundle.wfResult = wfo.run();
        } catch (Exception e) {
            log.warn("WFO failed for {}: {}", bundle.name, e.getMessage());
            bundle.wfResult = null;
        }

        if (bundle.fullResult != null && bundle.fullResult.totalTrades() > 5) {
            try {
                MonteCarloSimulation mc = new MonteCarloSimulation(bundle.fullResult, MONTE_CARLO_RUNS);
                bundle.mcResult = mc.run();
            } catch (Exception e) {
                log.warn("MC failed for {}: {}", bundle.name, e.getMessage());
                bundle.mcResult = null;
            }
        } else {
            bundle.mcResult = null;
        }

        if (bundle.wfResult != null && bundle.mcResult != null) {
            bundle.robustness = RobustnessScore.calculate(bundle.fullResult, bundle.wfResult, bundle.mcResult);
        } else {
            bundle.robustness = computeProxyRobustness(bundle.fullResult);
        }
    }

    private static RobustnessScore computeProxyRobustness(BacktestResult result) {
        if (result == null) return new RobustnessScore(0, 0, 0, 0, 0);

        double sharpe = result.sharpeRatio();
        double pf = result.profitFactor();
        double winRate = result.winRatePct();
        double maxDd = result.maxDrawdownPct();
        int trades = result.totalTrades();

        double wfScore = Math.clamp(normalizeSharpe(sharpe) * 2.5, 0, 100);
        double mcScore = Math.clamp(winRate * 0.8 + (trades > 10 ? 10 : trades), 0, 100);
        double ssScore = Math.clamp(100 - maxDd * 1.5, 0, 100);
        double psScore = Math.clamp(pf * 20, 0, 100);
        double overall = wfScore * 0.40 + mcScore * 0.30 + ssScore * 0.20 + psScore * 0.10;

        return new RobustnessScore(Math.clamp(overall, 0, 100), Math.clamp(wfScore, 0, 100),
            Math.clamp(mcScore, 0, 100), Math.clamp(ssScore, 0, 100), Math.clamp(psScore, 0, 100));
    }

    // ===============================================================
    //  Export
    // ===============================================================

    private static void exportResults(List<StrategyBundle> ranked, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path strategiesDir = outputDir.resolve("strategies");
        Files.createDirectories(strategiesDir);

        List<RankingDashboard.RankingEntry> rankingEntries = new ArrayList<>();
        for (var b : ranked) {
            BacktestResult result = b.fullResult != null ? b.fullResult : b.quickResult;
            RobustnessScore rob = b.robustness != null ? b.robustness : computeProxyRobustness(result);
            rankingEntries.add(new RankingDashboard.RankingEntry(b.rank, b.chromosome, result, rob, null));
        }

        // ranking.html
        String fullHtml = generateStyledHtml(rankingEntries, ranked);
        Files.writeString(outputDir.resolve("ranking.html"), fullHtml);
        log.info("  ranking.html ({} entries)", rankingEntries.size());

        // ranking.json
        Files.writeString(outputDir.resolve("ranking.json"), generateJson(rankingEntries));
        log.info("  ranking.json");

        // Top 20 Java files
        StrategyCodeGen codeGen = new StrategyCodeGen();
        int exportCount = 0;
        for (int i = 0; i < Math.min(EXPORT_TOP_N, ranked.size()); i++) {
            var b = ranked.get(i);
            String className = "Top" + (i + 1) + "_" + sanitizeClassName(b.name);
            try {
                String sourceCode = codeGen.generate(b.chromosome, className);
                Files.writeString(strategiesDir.resolve(className + ".java"), sourceCode);
                exportCount++;
            } catch (Exception e) {
                log.warn("Failed to export strategy {}: {}", className, e.getMessage());
            }
        }
        log.info("  {}/{} strategies exported as Java files", exportCount, Math.min(EXPORT_TOP_N, ranked.size()));

        // summary.txt
        Files.writeString(outputDir.resolve("summary.txt"), generateSummary(ranked));
        log.info("  summary.txt");
    }

    // ===============================================================
    //  HTML Report
    // ===============================================================

    private static String generateStyledHtml(
            List<RankingDashboard.RankingEntry> entries, List<StrategyBundle> bundles) {

        long total = bundles.size();
        long promising = bundles.stream().filter(b -> b.compositeScore >= 70).count();
        long medium = bundles.stream().filter(b -> b.compositeScore >= 40 && b.compositeScore < 70).count();
        long weak = bundles.stream().filter(b -> b.compositeScore < 40).count();
        long distPromW = total > 0 ? promising * 100 / total : 0;
        long distMedW = total > 0 ? medium * 100 / total : 0;
        long distWeakW = total > 0 ? weak * 100 / total : 0;

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            var b = i < bundles.size() ? bundles.get(i) : null;
            if (b == null) break;

            BacktestResult r = e.result();
            double sharpe = r != null ? r.sharpeRatio() : 0;
            double pf = r != null ? r.profitFactor() : 0;
            double dd = r != null ? r.maxDrawdownPct() : 0;
            double ret = r != null ? r.totalReturnPct() : 0;
            int trades = r != null ? r.totalTrades() : 0;
            double winRate = r != null ? r.winRatePct() : 0;
            double rob = e.robustness() != null ? e.robustness().overall() : 0;

            String badge = i < 3 ? "\uD83C\uDFC6" : String.valueOf(e.rank());
            String colorEmoji = b.compositeScore >= 70 ? "\uD83D\uDFE2" :
                b.compositeScore >= 40 ? "\uD83D\uDFE1" : "\uD83D\uDD34";

            rows.append(String.format(
                "<tr class=\"sql-row\" data-score=\"%.1f\" data-type=\"%s\" data-sharpe=\"%.4f\" data-pf=\"%.4f\" data-dd=\"%.4f\" data-ret=\"%.4f\">" +
                "<td><span class=\"rank-badge rank-%s\">%s</span></td>" +
                "<td><span class=\"strat-name\">%s</span><span class=\"strat-type type-%s\">%s</span>%s</td>" +
                "<td class=\"%s\">%.2f</td><td class=\"%s\">%.2f</td>" +
                "<td class=\"%s\">%.1f%%</td><td class=\"%s\">%.2f%%</td>" +
                "<td>%d</td><td>%.1f%%</td><td>%.1f</td></tr>",
                b.compositeScore, e.strategyType(), sharpe, pf, dd, ret,
                i < 3 ? (i == 0 ? "1" : i == 1 ? "2" : "3") : "other", badge,
                escHtml(b.name), e.strategyType(), e.strategyType(), colorEmoji,
                sharpe >= 1 ? "val-pos" : "val-neg", sharpe,
                pf >= 1.5 ? "val-pos" : "val-neutral", pf,
                dd < 20 ? "val-pos" : "val-neg", dd,
                ret >= 0 ? "val-pos" : "val-neg", ret,
                trades, winRate, rob));
        }

        String top5 = buildTop5Html(bundles);
        String labels = buildChartLabels(entries);
        String sharpeData = buildChartSharpeData(entries);
        String scoreData = buildChartScoreData(bundles);
        String ddData = buildChartDdData(entries);
        String typeDist = buildTypeDistribution(bundles);

        return String.format("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>StrategyQuant — Batch Ranking Dashboard</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0f1923;color:#e0e6ed;padding:20px}
.header{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;margin-bottom:12px}
.header h1{color:#00d4aa;font-size:26px;display:flex;align-items:center;gap:10px}
.header h1 .sq-badge{font-size:12px;background:#1a2d3d;color:#8899aa;padding:3px 10px;border-radius:4px;font-weight:400}
.controls{display:flex;gap:8px;align-items:center}
.card-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:12px}
.card{background:#1a2d3d;padding:12px 14px;border-radius:8px;border-left:3px solid #00d4aa}
.card .lbl{color:#8899aa;font-size:11px;text-transform:uppercase;letter-spacing:.5px}
.card .val{font-size:20px;font-weight:700;color:#e0e6ed;margin-top:4px}
.card .val.g{color:#00d4aa}.card .val.r{color:#ff6b6b}.card .val.y{color:#ffc107}
.dist{display:flex;height:5px;border-radius:3px;overflow:hidden;margin:6px 0 14px}
.dist-g{background:#00d4aa}.dist-y{background:#ffc107}.dist-r{background:#ff6b6b}
.filters{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:12px;align-items:center}
.filters .lb{color:#8899aa;font-size:12px;margin-right:4px}
.btn{padding:5px 12px;border-radius:6px;border:1px solid #2a4055;background:#1a2d3d;color:#8899aa;cursor:pointer;font-size:11px;transition:all .15s}
.btn:hover{background:#243b52;color:#c0d0e0}.btn.on{background:#00d4aa;color:#0f1923;font-weight:600}
.tc{overflow-x:auto;border-radius:8px;border:1px solid #1a2d3d}
table{width:100%%;border-collapse:collapse;font-size:12px}
th{background:#1a2d3d;color:#8899aa;padding:8px 10px;text-align:left;font-size:10px;text-transform:uppercase;letter-spacing:.3px;cursor:pointer;user-select:none;position:sticky;top:0;z-index:1}
th:hover{color:#e0e6ed}
td{padding:7px 10px;border-bottom:1px solid #1a2d3d;white-space:nowrap}
tr:hover td{background:#1e3347}tr.h{display:none}
.sn{font-weight:600;color:#e0e6ed}
.st{display:inline-block;font-size:8px;padding:1px 5px;border-radius:8px;margin-left:5px;font-weight:500;vertical-align:middle}
.st-T{background:#1a3a2a;color:#00d4aa}.st-M{background:#2a1a3a;color:#bb86fc}.st-B{background:#3a2a1a;color:#ffb74d}
.ct{font-size:14px;margin-left:3px;vertical-align:middle}
.vp{color:#00d4aa;font-weight:600}.vn{color:#ff6b6b;font-weight:600}.vm{color:#e0e6ed}
.cg{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:12px;margin:16px 0}
.cc{background:#1a2d3d;padding:14px;border-radius:8px}
.cc h3{color:#8899aa;font-size:12px;margin-bottom:10px;font-weight:500}
.cc canvas{max-height:200px;width:100%%}
.t5{background:#1a2d3d;border-radius:8px;padding:14px;margin-bottom:14px;border:1px solid #00d4aa}
.t5 h2{color:#00d4aa;font-size:15px;margin-bottom:10px}
.t5g{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}
.t5c{background:#243b52;border-radius:6px;padding:10px}
.t5c .rk{font-size:10px;color:#8899aa}
.t5c .nm{font-weight:600;font-size:13px;color:#e0e6ed;margin:2px 0}
.t5c .mt{font-size:11px;color:#8899aa}
.t5c .mt sp{color:#00d4aa;font-weight:600}
@media(max-width:768px){.cg{grid-template-columns:1fr}.t5g{grid-template-columns:1fr}}
</style>
</head>
<body>
<div class="header">
  <h1>&#x1F3C6; Batch Strategy Generator <span class="sq-badge">StrategyQuant-style</span></h1>
  <div class="controls">
    <label class="btn on" onclick="setTop(20)">Top 20</label>
    <label class="btn" onclick="setTop(50)">Top 50</label>
    <label class="btn" onclick="setTop(100)">Top 100</label>
  </div>
</div>
<div class="card-grid">
  <div class="card"><div class="lbl">Total</div><div class="val">%d</div></div>
  <div class="card" style="border-left-color:#00d4aa"><div class="lbl">&#x1F7E2; Promising</div><div class="val g">%d</div></div>
  <div class="card" style="border-left-color:#ffc107"><div class="lbl">&#x1F7E1; Medium</div><div class="val y">%d</div></div>
  <div class="card" style="border-left-color:#ff6b6b"><div class="lbl">&#x1F534; Weak</div><div class="val r">%d</div></div>
</div>
<div class="dist"><div class="dist-g" style="width:%d%%"></div><div class="dist-y" style="width:%d%%"></div><div class="dist-r" style="width:%d%%"></div></div>
<div class="t5"><h2>&#x1F947; Top 5 at a Glance</h2><div class="t5g" id="top5grid">%s</div></div>
<div class="cg">
  <div class="cc"><h3>&#x1F4CA; Sharpe Distribution (Top 20)</h3><canvas id="c1"></canvas></div>
  <div class="cc"><h3>&#x1F4CA; Type Distribution</h3><canvas id="c2"></canvas></div>
  <div class="cc"><h3>&#x1F4CA; Score vs Drawdown</h3><canvas id="c3"></canvas></div>
</div>
<div class="filters">
  <span class="lb">&#x1F3AF; Filter:</span>
  <div class="btn on" onclick="filt('all')">All</div>
  <div class="btn" onclick="filt('Trend')">Trend</div>
  <div class="btn" onclick="filt('MeanRev')">MeanRev</div>
  <div class="btn" onclick="filt('Breakout')">Breakout</div>
  <span class="lb" style="margin-left:12px">&#x2195; Sort:</span>
  <div class="btn on" onclick="srt('score')">Score</div>
  <div class="btn" onclick="srt('sharpe')">Sharpe</div>
  <div class="btn" onclick="srt('pf')">PF</div>
  <div class="btn" onclick="srt('dd')">MaxDD</div>
</div>
<div class="tc">
<table id="tbl">
<thead><tr><th>#</th><th>Strategy</th><th onclick="srt('sharpe')">Sharpe &#x2195;</th><th onclick="srt('pf')">PF &#x2195;</th><th onclick="srt('dd')">MaxDD &#x2195;</th><th onclick="srt('ret')">Return &#x2195;</th><th>Trades</th><th>WinRate</th><th>Robust.</th></tr></thead>
<tbody>%s</tbody></table></div>
<script>
function filt(t){document.querySelectorAll('.filters .btn').forEach(b=>b.classList.remove('on'));event.target.classList.add('on');
document.querySelectorAll('.sql-row').forEach(r=>{r.classList.toggle('h',t!='all'&&r.dataset.type!=t)})}
function setTop(n){document.querySelectorAll('.controls .btn').forEach(b=>b.classList.remove('on'));event.target.classList.add('on');
document.querySelectorAll('.sql-row').forEach((r,i)=>{r.classList.toggle('h',i>=n)})}
var asc={};
function srt(k){var t=document.querySelector('#tbl tbody'),r=Array.from(t.querySelectorAll('.sql-row:not(.h)'));
asc[k]=!asc[k];r.sort((a,b)=>{var va=parseFloat(a.dataset[k])||0,vb=parseFloat(b.dataset[k])||0;return asc[k]?va-vb:vb-va});
r.forEach(x=>t.appendChild(x))}
var labs=%s,sd=%s,sc=%s,dd=%s;
new Chart(document.getElementById('c1'),{type:'bar',data:{labels:labs.slice(0,20),datasets:[{label:'Sharpe',data:sd.slice(0,20),backgroundColor:sd.slice(0,20).map(v=>v>=1?'#00d4aa':v>0?'#ffc107':'#ff6b6b'),borderRadius:3}]},options:{responsive:true,plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#8899aa',font:{size:9}}},y:{ticks:{color:'#8899aa'}}}}});
new Chart(document.getElementById('c2'),{type:'doughnut',data:{labels:['Trend','MeanRev','Breakout'],datasets:[{data:%s,backgroundColor:['#00d4aa','#bb86fc','#ffb74d']}]},options:{responsive:true,plugins:{legend:{position:'bottom',labels:{color:'#8899aa',padding:12}}}}});
new Chart(document.getElementById('c3'),{type:'scatter',data:{datasets:[{label:'Strategies',data:sc.map((s,i)=>({x:s,y:dd[i]})),backgroundColor:sc.map(s=>s>=70?'#00d4aa':s>=40?'#ffc107':'#ff6b6b'),pointRadius:4}]},options:{responsive:true,plugins:{legend:{display:false}},scales:{x:{title:{display:true,text:'Score',color:'#8899aa'},ticks:{color:'#8899aa'}},y:{title:{display:true,text:'MaxDD %%',color:'#8899aa'},ticks:{color:'#8899aa'}}}}});
</script>
</body>
</html>""", total, promising, medium, weak, distPromW, distMedW, distWeakW, top5, rows, labels, sharpeData, scoreData, ddData, typeDist);
    }

    private static String buildTop5Html(List<StrategyBundle> bundles) {
        StringBuilder sb = new StringBuilder();
        String[] medals = {"\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49", "4th", "5th"};
        for (int i = 0; i < Math.min(5, bundles.size()); i++) {
            var b = bundles.get(i);
            String colorTag = b.compositeScore >= 70 ? "\uD83D\uDFE2" : b.compositeScore >= 40 ? "\uD83D\uDFE1" : "\uD83D\uDD34";
            sb.append(String.format(
                "<div class=\"t5c\"><div class=\"rk\">%s #%d</div><div class=\"nm\">%s %s</div>" +
                "<div class=\"mt\">Sharpe: <sp>%.2f</sp> | PF: <sp>%.2f</sp><br>" +
                "Return: <sp>%.2f%%%%</sp> | DD: <sp>%.1f%%%%</sp><br>Trades: <sp>%d</sp></div></div>",
                medals[i], b.rank, escHtml(b.name), colorTag,
                b.quickSharpe, b.quickPf, b.quickReturn, b.quickMaxDd, b.quickTrades));
        }
        return sb.toString();
    }

    // ===============================================================
    //  JSON, Summary, Chart Data
    // ===============================================================

    private static String generateJson(List<RankingDashboard.RankingEntry> entries) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            var r = e.result();
            double sharpe = r != null ? r.sharpeRatio() : 0;
            double pf = r != null ? r.profitFactor() : 0;
            double dd = r != null ? r.maxDrawdownPct() : 0;
            double ret = r != null ? r.totalReturnPct() : 0;
            int trades = r != null ? r.totalTrades() : 0;
            double winRate = r != null ? r.winRatePct() : 0;
            double rob = e.robustness() != null ? e.robustness().overall() : 0;
            sb.append(String.format(
                "{\"rank\":%d,\"name\":\"%s\",\"type\":\"%s\",\"sharpe\":%.4f," +
                "\"profitFactor\":%.4f,\"maxDrawdownPct\":%.2f," +
                "\"totalReturnPct\":%.2f,\"totalTrades\":%d,\"winRatePct\":%.2f,\"robustness\":%.1f}",
                e.rank(), escJson(e.strategyName()), escJson(e.strategyType()),
                sharpe, pf, dd, ret, trades, winRate, rob));
            sb.append(i < entries.size() - 1 ? ",\n" : "\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String generateSummary(List<StrategyBundle> bundles) {
        StringBuilder sb = new StringBuilder();
        sb.append("BATCH STRATEGY GENERATOR — SUMMARY\n");
        sb.append("========================================\n\n");

        long total = bundles.size();
        long promising = bundles.stream().filter(b -> b.compositeScore >= 70).count();
        long medium = bundles.stream().filter(b -> b.compositeScore >= 40 && b.compositeScore < 70).count();
        long weak = bundles.stream().filter(b -> b.compositeScore < 40).count();

        sb.append(String.format("Total: %d | Promising: %d | Medium: %d | Weak: %d\n\n", total, promising, medium, weak));
        sb.append("Top 10 Strategies:\n");
        sb.append(String.format("%-4s %-24s %-6s %-6s %-6s %-8s %-6s %-6s\n",
            "Rank", "Name", "Sharpe", "PF", "DD%", "Ret%", "Trades", "Score"));

        for (int i = 0; i < Math.min(10, bundles.size()); i++) {
            var b = bundles.get(i);
            String emoji = b.compositeScore >= 70 ? "\uD83D\uDFE2" : b.compositeScore >= 40 ? "\uD83D\uDFE1" : "\uD83D\uDD34";
            sb.append(String.format("%-4d %-24s %-6.2f %-6.2f %-6.1f %-8.2f %-6d %s%.1f\n",
                b.rank, trunc(b.name, 24), b.quickSharpe, b.quickPf,
                b.quickMaxDd, b.quickReturn, b.quickTrades, emoji, b.compositeScore));
        }

        if (bundles.size() > 10) {
            sb.append("  ... ").append(bundles.size() - 10).append(" more strategies\n");
        }

        sb.append("\nType Distribution:\n");
        Map<StrategyBuilder.StrategyType, Long> tc = bundles.stream()
            .collect(Collectors.groupingBy(b -> b.type, Collectors.counting()));
        for (var e : tc.entrySet()) {
            sb.append(String.format("  %-20s %d\n", e.getKey(), e.getValue()));
        }

        int validated = (int) bundles.stream().filter(b -> b.fullResult != null).count();
        sb.append(String.format("\nValidated: %d\n", validated));
        sb.append("Files: ranking.html | ranking.json | summary.txt\n");

        return sb.toString();
    }

    private static String buildChartLabels(List<RankingDashboard.RankingEntry> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(20, entries.size()); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escJson(entries.get(i).strategyName())).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String buildChartSharpeData(List<RankingDashboard.RankingEntry> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(20, entries.size()); i++) {
            if (i > 0) sb.append(",");
            var r = entries.get(i).result();
            sb.append(r != null ? r.sharpeRatio() : 0);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String buildChartScoreData(List<StrategyBundle> bundles) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(50, bundles.size()); i++) {
            if (i > 0) sb.append(",");
            sb.append(bundles.get(i).compositeScore);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String buildChartDdData(List<RankingDashboard.RankingEntry> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(50, entries.size()); i++) {
            if (i > 0) sb.append(",");
            var r = entries.get(i).result();
            sb.append(r != null ? r.maxDrawdownPct() : 0);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String buildTypeDistribution(List<StrategyBundle> bundles) {
        long trend = bundles.stream().filter(sb -> sb.type == StrategyBuilder.StrategyType.TREND_FOLLOWING).count();
        long meanRev = bundles.stream().filter(sb -> sb.type == StrategyBuilder.StrategyType.MEAN_REVERSION).count();
        long breakout = bundles.stream().filter(sb -> sb.type == StrategyBuilder.StrategyType.BREAKOUT).count();
        return "[" + trend + "," + meanRev + "," + breakout + "]";
    }

    // ===============================================================
    //  Helpers
    // ===============================================================

    private static String colorLabel(double score) {
        return score >= 70 ? "\uD83D\uDFE2" : score >= 40 ? "\uD83D\uDFE1" : "\uD83D\uDD34";
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String trunc(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max - 3) + "...";
    }

    private static String format2(double v) { return String.format("%.2f", v); }

    private static int clampPeriod(int p, int min, int max) {
        return Math.clamp(p, min, max);
    }

    private static String sanitizeClassName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("^[^a-zA-Z_]", "_").replaceAll("__+", "_");
    }

    // ===============================================================
    //  Bar generation
    // ===============================================================

    private static List<Bar> generateBars(int count) {
        var rng = ThreadLocalRandom.current();
        List<Bar> bars = new ArrayList<>(count);
        double price = 1.1000;
        for (int i = 0; i < count; i++) {
            double change = (rng.nextDouble() - 0.45) * 0.02;
            price = price * (1 + change);
            double open = price;
            double close = price * (1 + (rng.nextDouble() - 0.5) * 0.01);
            double high = Math.max(open, close) * (1 + rng.nextDouble() * 0.005);
            double low = Math.min(open, close) * (1 - rng.nextDouble() * 0.005);
            long volume = rng.nextLong(100, 10000);
            bars.add(new Bar("EURUSD", Instant.ofEpochSecond(SYNTHETIC_BAR_SECS * i), open, high, low, close, volume));
        }
        return bars;
    }

    // ===============================================================
    //  Real data loading
    // ===============================================================
    
    private static List<Bar> loadCSVData(String csvPath) {
        var bars = new ArrayList<Bar>();
        try {
            var path = java.nio.file.Paths.get(csvPath);
            if (!java.nio.file.Files.exists(path)) {
                log.warn("CSV not found: {}. Using generated data.", csvPath);
                return null;
            }
            try (var reader = java.nio.file.Files.newBufferedReader(path)) {
                String header = reader.readLine(); // skip header
                String line;
                int i = 0;
                long baseSecs = 1700000000L; // fallback timestamp
                while ((line = reader.readLine()) != null && i < 10000) {
                    var parts = line.split(",");
                    if (parts.length >= 5) {
                        double open = Double.parseDouble(parts[1]);
                        double high = Double.parseDouble(parts[2]);
                        double low = Double.parseDouble(parts[3]);
                        double close = Double.parseDouble(parts[4]);
                        long volume = parts.length >= 6 ? Long.parseLong(parts[5]) : 0;
                        long ts = baseSecs + i * 3600L;
                        bars.add(new Bar("FOREX", java.time.Instant.ofEpochSecond(ts), open, high, low, close, volume));
                        i++;
                    }
                }
            }
            log.info("Loaded {} bars from {}", bars.size(), csvPath);
        } catch (Exception e) {
            log.warn("Failed to load CSV {}: {}. Using generated data.", csvPath, e.getMessage());
            return null;
        }
        return bars;
    }

    /**
     * Loads bars for the quick screening phase.
     * Uses real CSV data when available, falls back to synthetic data.
     * Truncates to maxBars to keep the quick screen fast.
     */
    private static List<Bar> loadScreenBars(String dataPath, int maxBars) {
        if (dataPath != null && !dataPath.isEmpty()) {
            List<Bar> realBars = loadCSVData(dataPath);
            if (realBars != null && !realBars.isEmpty()) {
                int takeCount = Math.min(realBars.size(), maxBars);
                List<Bar> truncated = realBars.subList(0, takeCount);
                log.info("Using real market data for quick screen: {} bars (loaded {}, truncated to {})",
                    takeCount, realBars.size(), maxBars);
                return truncated;
            }
        }
        log.info("No real data available — generating {} synthetic bars for quick screen", maxBars);
        return generateBars(maxBars);
    }

    // ===============================================================
    //  Types resolution
    // ===============================================================

    private static List<StrategyBuilder.StrategyType> resolveTypes(String typesSpec) {
        if ("all".equalsIgnoreCase(typesSpec)) {
            return List.of(
                StrategyBuilder.StrategyType.TREND_FOLLOWING,
                StrategyBuilder.StrategyType.MEAN_REVERSION,
                StrategyBuilder.StrategyType.BREAKOUT,
                StrategyBuilder.StrategyType.MOMENTUM
            );
        }
        return Arrays.stream(typesSpec.split(","))
            .map(String::trim)
            .map(s -> switch (s.toLowerCase()) {
                case "trend" -> StrategyBuilder.StrategyType.TREND_FOLLOWING;
                case "meanrev" -> StrategyBuilder.StrategyType.MEAN_REVERSION;
                case "breakout" -> StrategyBuilder.StrategyType.BREAKOUT;
                case "momentum" -> StrategyBuilder.StrategyType.MOMENTUM;
                default -> throw new IllegalArgumentException("Unknown type: " + s);
            })
            .toList();
    }

    // ===============================================================
    //  Config & Arg Parsing
    // ===============================================================

    record Config(
        int count,
        String types,
        int bars,
        double capital,
        Path outputDir,
        int threads,
        String dataPath,
        SelectionCriteria selectionCriteria
    ) {}

    static Config parseArgs(String[] args) {
        int count = 500;
        String types = "all";
        int bars = 250;
        double capital = 100_000.0;
        Path outputDir = Path.of("./batch-results/");
        String dataPath = null;
        int threads = Runtime.getRuntime().availableProcessors();

        double minSharpe = SelectionCriteria.DEFAULT_MIN_SHARPE;
        double minPf = SelectionCriteria.DEFAULT_MIN_PF;
        double maxDd = SelectionCriteria.DEFAULT_MAX_DD;
        double minWinRate = SelectionCriteria.DEFAULT_MIN_WIN_RATE;
        int targetCount = SelectionCriteria.DEFAULT_TARGET_COUNT;
        int maxAttempts = SelectionCriteria.DEFAULT_MAX_ATTEMPTS;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--count" -> count = parseIntArg(args, ++i, 500);
                case "--types" -> types = args[++i];
                case "--bars" -> bars = parseIntArg(args, ++i, 250);
                case "--capital" -> capital = parseDoubleArg(args, ++i, 100_000.0);
                case "--output" -> outputDir = Path.of(args[++i]);
                case "--threads" -> threads = parseIntArg(args, ++i, Runtime.getRuntime().availableProcessors());
                case "--data" -> dataPath = args[++i];
                case "--min-sharpe" -> minSharpe = parseDoubleArg(args, ++i, SelectionCriteria.DEFAULT_MIN_SHARPE);
                case "--min-pf" -> minPf = parseDoubleArg(args, ++i, SelectionCriteria.DEFAULT_MIN_PF);
                case "--max-dd" -> maxDd = parseDoubleArg(args, ++i, SelectionCriteria.DEFAULT_MAX_DD);
                case "--min-win-rate" -> minWinRate = parseDoubleArg(args, ++i, SelectionCriteria.DEFAULT_MIN_WIN_RATE);
                case "--target" -> targetCount = parseIntArg(args, ++i, SelectionCriteria.DEFAULT_TARGET_COUNT);
                case "--max-attempts" -> maxAttempts = parseIntArg(args, ++i, SelectionCriteria.DEFAULT_MAX_ATTEMPTS);
                default -> log.warn("Unknown arg: {}", args[i]);
            }
        }

        var criteria = new SelectionCriteria(minSharpe, minPf, maxDd, minWinRate, targetCount, maxAttempts);
        return new Config(count, types, bars, capital, outputDir, threads, dataPath, criteria);
    }

    private static int parseIntArg(String[] args, int i, int defaultValue) {
        if (i >= args.length) return defaultValue;
        try { return Integer.parseInt(args[i]); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static double parseDoubleArg(String[] args, int i, double defaultValue) {
        if (i >= args.length) return defaultValue;
        try { return Double.parseDouble(args[i]); } catch (NumberFormatException e) { return defaultValue; }
    }

    // ===============================================================
    //  Strategy Bundle
    // ===============================================================

    static class StrategyBundle {
        final String name;
        final StrategyBuilder.StrategyType type;
        final Chromosome chromosome;
        int rank;
        boolean passedSelection;
        BacktestResult quickResult;
        double quickSharpe, quickPf, quickWinRate, quickMaxDd, quickReturn;
        int quickTrades;
        double compositeScore;
        BacktestResult fullResult;
        WalkForwardOptimizer.Result wfResult;
        MonteCarloSimulation.Result mcResult;
        RobustnessScore robustness;

        StrategyBundle(String name, StrategyBuilder.StrategyType type, Chromosome chromosome) {
            this.name = name;
            this.type = type;
            this.chromosome = chromosome;
        }
    }
}
