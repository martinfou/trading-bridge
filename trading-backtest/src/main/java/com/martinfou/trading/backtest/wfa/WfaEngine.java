package com.martinfou.trading.backtest.wfa;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.ForexMarketCalendar;
import com.martinfou.trading.core.ForexPnL;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.strategy.ParameterRange;
import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.PerformanceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Main Walk-Forward Analysis engine.
 * Handles contiguous data slicing, parallel deterministic Grid Search, boundary trade purging,
 * and chronological reconstruction of the unified Out-of-Sample equity curve.
 */
public class WfaEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WfaEngine.class);

    private static final Map<String, java.lang.reflect.Field> fieldCache = new ConcurrentHashMap<>();
    private static final java.lang.reflect.Field ABSENT_FIELD;
    private int dummyAbsentFieldSentinel;
    static {
        java.lang.reflect.Field f = null;
        try {
            f = WfaEngine.class.getDeclaredField("dummyAbsentFieldSentinel");
        } catch (NoSuchFieldException ignored) {}
        ABSENT_FIELD = f;
    }

    private final WfaConfig config;
    private final Supplier<Strategy> strategySupplier;
    private final List<Bar> bars;
    private final ExecutorService executor;

    // Cost configurations
    private double commissionFixed = 0.0;
    private double commissionPct = 0.0;
    private double slippageFixed = 0.0;
    private double slippagePct = 0.0;
    private double stopSlippagePct = 0.0;
    private double usdJpyRate = ForexPnL.DEFAULT_USD_JPY;

    public WfaEngine(WfaConfig config, Supplier<Strategy> strategySupplier, List<Bar> bars) {
        this.config = Objects.requireNonNull(config, "config");
        this.strategySupplier = Objects.requireNonNull(strategySupplier, "strategySupplier");
        this.bars = Objects.requireNonNull(bars, "bars");
        if (bars.size() < 2) {
            throw new IllegalArgumentException("At least 2 bars are required for Walk-Forward Analysis");
        }
        for (Bar bar : bars) {
            if (bar == null) {
                throw new IllegalArgumentException("bars list cannot contain null elements");
            }
        }
        Strategy s1 = strategySupplier.get();
        Strategy s2 = strategySupplier.get();
        if (s1 == s2) {
            throw new IllegalArgumentException("strategySupplier must return new instances on each call");
        }

        // Limit CPU utilization to 80% using a traditional ThreadPoolExecutor
        int numThreads = Math.max(1, (int) (Runtime.getRuntime().availableProcessors() * 0.8));
        this.executor = new ThreadPoolExecutor(
            numThreads, numThreads,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100000),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setName(String.format("wfa-worker-%d", count.getAndIncrement()));
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("Initialized WfaEngine with ThreadPoolExecutor of size: {}", numThreads);
    }

    public WfaEngine withCommissionFixed(double commissionFixed) {
        if (Double.isNaN(commissionFixed) || Double.isInfinite(commissionFixed) || commissionFixed < 0) {
            throw new IllegalArgumentException("commissionFixed must be non-negative and finite");
        }
        this.commissionFixed = commissionFixed;
        return this;
    }

    public WfaEngine withCommissionPct(double commissionPct) {
        if (Double.isNaN(commissionPct) || Double.isInfinite(commissionPct) || commissionPct < 0) {
            throw new IllegalArgumentException("commissionPct must be non-negative and finite");
        }
        this.commissionPct = commissionPct;
        return this;
    }

    public WfaEngine withSlippageFixed(double slippageFixed) {
        if (Double.isNaN(slippageFixed) || Double.isInfinite(slippageFixed) || slippageFixed < 0) {
            throw new IllegalArgumentException("slippageFixed must be non-negative and finite");
        }
        this.slippageFixed = slippageFixed;
        return this;
    }

    public WfaEngine withSlippagePct(double slippagePct) {
        if (Double.isNaN(slippagePct) || Double.isInfinite(slippagePct) || slippagePct < 0) {
            throw new IllegalArgumentException("slippagePct must be non-negative and finite");
        }
        this.slippagePct = slippagePct;
        return this;
    }

    public WfaEngine withStopSlippagePct(double stopSlippagePct) {
        if (Double.isNaN(stopSlippagePct) || Double.isInfinite(stopSlippagePct) || stopSlippagePct < 0) {
            throw new IllegalArgumentException("stopSlippagePct must be non-negative and finite");
        }
        this.stopSlippagePct = stopSlippagePct;
        return this;
    }

    public WfaEngine withUsdJpyRate(double usdJpyRate) {
        if (Double.isNaN(usdJpyRate) || Double.isInfinite(usdJpyRate) || usdJpyRate <= 0) {
            throw new IllegalArgumentException("usdJpyRate must be positive and finite");
        }
        this.usdJpyRate = usdJpyRate;
        return this;
    }

    /**
     * Executes the Walk-Forward Analysis and returns the consolidated report.
     */
    public WfaReport execute() {
        String wfaId = UUID.randomUUID().toString();
        log.info("Starting WFA execution: ID={}", wfaId);

        List<Trade> activeCrossingTrades = new ArrayList<>();

        // 1. Slice data into contiguous folds
        List<WfaFold> folds = generateFolds();
        if (folds.isEmpty()) {
            throw new IllegalStateException("Insufficient historical data to generate at least one Walk-Forward fold");
        }

        // 2. Generate all parameter combinations
        List<Map<String, Double>> combinations = generateCombinations(config.parameterRanges());
        log.info("WFA combinations count: {}", combinations.size());

        List<WfaFoldResult> foldsResults = new ArrayList<>();
        List<Trade> allOosTrades = new ArrayList<>();
        List<Double> globalOosEquityCurve = new ArrayList<>();
        double currentGlobalEquity = config.initialCapital();
        double periodsPerYear = PerformanceMetrics.detectPeriodsPerYear(bars);

        // 3. Process each fold sequentially (Grid Search on In-Sample, validation on Out-of-Sample)
        for (WfaFold fold : folds) {
            log.info("Processing Fold {}/{} | IS: [{} - {}[ | OOS: [{} - {}]", 
                fold.index() + 1, folds.size(), fold.isStart(), fold.isEnd(), fold.oosStart(), fold.oosEnd());

            List<Bar> isBars = filterBars(bars, fold.isStart(), fold.isEnd(), false);

            // Run parallel grid search on In-Sample bars
            EvaluationResult best = runGridSearch(isBars, combinations);
            if (best == null) {
                log.warn("No valid parameters found for Fold {}", fold.index());
                continue;
            }
            log.info("Fold {} selected parameters: {} (IS Sharpe: {}, IS Trades: {})", 
                fold.index(), best.combination, best.sharpeRatio, best.tradesCount);

            // Run backtest on combined [isStart, oosEnd] period using the selected best parameters
            List<Bar> combinedBars = filterBars(bars, fold.isStart(), fold.oosEnd(), true);
            Strategy oosStrategy = strategySupplier.get();
            applyParameters(oosStrategy, best.combination);

            // Wrap strategy to inject crossing trade state right before the first bar >= fold.oosStart()
            final Strategy finalOosStrategy = oosStrategy;
            final List<Trade> currentCrossingTrades = new ArrayList<>(activeCrossingTrades);
            Strategy wrappedStrategy = new Strategy() {
                private boolean synchronizedCrossing = false;
                @Override
                public String name() { return finalOosStrategy.name(); }
                @Override
                public void onBar(Bar bar) {
                    if (!synchronizedCrossing && !bar.timestamp().isBefore(fold.oosStart())) {
                        synchronizedCrossing = true;
                        syncCrossingTradesToStrategy(finalOosStrategy, currentCrossingTrades, bar);
                    }
                    finalOosStrategy.onBar(bar);
                }
                @Override
                public void onTick(double bid, double ask, long volume) {
                    finalOosStrategy.onTick(bid, ask, volume);
                }
                @Override
                public List<Order> getPendingOrders() {
                    return finalOosStrategy.getPendingOrders();
                }
                @Override
                public void reset() {
                    finalOosStrategy.reset();
                    synchronizedCrossing = false;
                }
            };

            BacktestEngine oosEngine = new BacktestEngine(wrappedStrategy, combinedBars, config.initialCapital())
                .withCommissionFixed(commissionFixed)
                .withCommissionPct(commissionPct)
                .withSlippageFixed(slippageFixed)
                .withSlippagePct(slippagePct)
                .withStopSlippagePct(stopSlippagePct)
                .withUsdJpyRate(usdJpyRate);

            BacktestResult combinedResult = oosEngine.run();

            // Sieve trades into In-Sample and Out-of-Sample
            List<Trade> isTrades = new ArrayList<>();
            List<Trade> oosTradesRaw = new ArrayList<>();
            boolean isLastFold = (fold.index() == folds.size() - 1);
            for (Trade t : combinedResult.trades()) {
                if (t.entryTime().isBefore(fold.oosStart())) {
                    isTrades.add(t);
                } else {
                    boolean inOos = isLastFold ? !t.entryTime().isAfter(fold.oosEnd()) : t.entryTime().isBefore(fold.oosEnd());
                    if (inOos) {
                        oosTradesRaw.add(t);
                    }
                }
            }

            // Calculate max position duration on IS trades for purging margin
            Duration maxDuration = Duration.ZERO;
            for (Trade t : isTrades) {
                Duration dur = Duration.between(t.entryTime(), t.exitTime());
                if (dur.compareTo(maxDuration) > 0) {
                    maxDuration = dur;
                }
            }

            // Purge boundary trades (IS trades that spill over or enter too close to OOS)
            List<Trade> purgedTrades = new ArrayList<>();
            for (Trade t : isTrades) {
                boolean overlaps = !t.exitTime().isBefore(fold.oosStart());
                boolean tooClose = !t.entryTime().isBefore(fold.oosStart().minus(maxDuration));
                if (overlaps || tooClose) {
                    purgedTrades.add(t);
                }
            }

            // Filter combined trading bars
            List<Bar> combinedTradingBars = combinedBars.stream()
                .filter(ForexMarketCalendar::isTradingBar)
                .toList();

            // Locate where OOS period starts and ends in the combined trading bars
            int startOosIdx = -1;
            int endOosIdx = -1;
            for (int i = 0; i < combinedTradingBars.size(); i++) {
                Instant ts = combinedTradingBars.get(i).timestamp();
                if (startOosIdx == -1 && !ts.isBefore(fold.oosStart())) {
                    startOosIdx = i;
                }
                if (startOosIdx != -1) {
                    if (isLastFold) {
                        if (ts.isAfter(fold.oosEnd())) {
                            endOosIdx = i;
                            break;
                        }
                    } else {
                        if (!ts.isBefore(fold.oosEnd())) {
                            endOosIdx = i;
                            break;
                        }
                    }
                }
            }
            if (endOosIdx == -1) {
                endOosIdx = combinedTradingBars.size();
            }

            // Calculate OOS metrics for the fold
            double oosSharpe = 0.0;
            double oosReturnPct = 0.0;
            double oosMaxDrawdownPct = 0.0;

            List<Double> foldOosCurve = new ArrayList<>();
            if (startOosIdx >= 0 && startOosIdx < endOosIdx && startOosIdx < combinedResult.equityCurve().size()) {
                int limit = Math.min(endOosIdx, combinedResult.equityCurve().size());
                double baseEquity;
                if (startOosIdx > 0) {
                    Bar baseBar = combinedTradingBars.get(startOosIdx - 1);
                    double origBase = combinedResult.equityCurve().get(startOosIdx - 1);
                    double adjustment = 0.0;
                    for (Trade t : purgedTrades) {
                        adjustment += computeTradeContribution(t, baseBar);
                    }
                    double crossingContrib = 0.0;
                    for (Trade t : activeCrossingTrades) {
                        crossingContrib += computeTradeContribution(t, baseBar);
                    }
                    baseEquity = origBase - adjustment + crossingContrib;
                } else {
                    baseEquity = config.initialCapital();
                }
                foldOosCurve.add(baseEquity);

                for (int i = startOosIdx; i < limit; i++) {
                    Bar bar = combinedTradingBars.get(i);
                    double origEquity = combinedResult.equityCurve().get(i);
                    double adjustment = 0.0;
                    for (Trade t : purgedTrades) {
                        adjustment += computeTradeContribution(t, bar);
                    }
                    double crossingContrib = 0.0;
                    for (Trade t : activeCrossingTrades) {
                        crossingContrib += computeTradeContribution(t, bar);
                    }
                    foldOosCurve.add(origEquity - adjustment + crossingContrib);
                }

                // OOS return
                oosReturnPct = baseEquity != 0.0 ? ((foldOosCurve.getLast() - baseEquity) / baseEquity) * 100.0 : 0.0;

                // OOS max drawdown
                double peakEquity = foldOosCurve.getFirst();
                double maxDd = 0.0;
                for (double e : foldOosCurve) {
                    if (e > peakEquity) peakEquity = e;
                    double dd = peakEquity > 0.0 ? (peakEquity - e) / peakEquity * 100.0 : 0.0;
                    if (dd > maxDd) maxDd = dd;
                }
                oosMaxDrawdownPct = maxDd;

                // OOS Sharpe ratio
                List<Double> foldOosReturns = new ArrayList<>();
                for (int i = 1; i < foldOosCurve.size(); i++) {
                    double prevVal = foldOosCurve.get(i - 1);
                    if (prevVal != 0.0) {
                        foldOosReturns.add((foldOosCurve.get(i) - prevVal) / prevVal);
                    }
                }
                oosSharpe = PerformanceMetrics.sharpeRatio(foldOosReturns, PerformanceMetrics.DEFAULT_RISK_FREE_RATE, periodsPerYear);

                // Chronological reconstruction of overall OOS equity curve
                for (int j = 1; j < foldOosCurve.size(); j++) {
                    double prevVal = foldOosCurve.get(j - 1);
                    if (prevVal != 0.0) {
                        double ratio = foldOosCurve.get(j) / prevVal;
                        currentGlobalEquity *= ratio;
                    } else {
                        currentGlobalEquity += foldOosCurve.get(j);
                    }
                    globalOosEquityCurve.add(currentGlobalEquity);
                }
            }

            // Track unpurged OOS trades
            allOosTrades.addAll(oosTradesRaw);

            // Prune active crossing trades that closed during this fold's OOS
            activeCrossingTrades.removeIf(t -> !t.exitTime().isAfter(fold.oosEnd()));

            // Append new boundary crossing trades from this fold's OOS
            for (Trade t : oosTradesRaw) {
                if (t.exitTime().isAfter(fold.oosEnd())) {
                    activeCrossingTrades.add(t);
                }
            }

            // Record fold results
            foldsResults.add(new WfaFoldResult(
                fold.index(),
                fold.isStart().toString(),
                fold.isEnd().toString(),
                fold.oosStart().toString(),
                fold.oosEnd().toString(),
                best.combination,
                sanitizeDouble(best.sharpeRatio),
                sanitizeDouble(oosSharpe),
                best.tradesCount,
                oosTradesRaw.size(),
                sanitizeDouble(best.returnPct),
                sanitizeDouble(oosReturnPct),
                sanitizeDouble(best.maxDrawdownPct),
                sanitizeDouble(oosMaxDrawdownPct),
                purgedTrades.size()
            ));
        }

        // 4. Compute overall OOS metrics
        double globalOosReturnPct = config.initialCapital() > 0 ?
            ((currentGlobalEquity - config.initialCapital()) / config.initialCapital()) * 100.0 : 0.0;

        double peak = config.initialCapital();
        double globalOosMaxDrawdownPct = 0.0;
        for (double e : globalOosEquityCurve) {
            if (e > peak) peak = e;
            double dd = (peak - e) / peak * 100.0;
            if (dd > globalOosMaxDrawdownPct) globalOosMaxDrawdownPct = dd;
        }

        List<Double> globalOosReturns = new ArrayList<>();
        double prev = config.initialCapital();
        for (double e : globalOosEquityCurve) {
            if (prev != 0.0) {
                globalOosReturns.add((e - prev) / prev);
            }
            prev = e;
        }
        double globalOosSharpe = PerformanceMetrics.sharpeRatio(globalOosReturns, PerformanceMetrics.DEFAULT_RISK_FREE_RATE, periodsPerYear);

        List<Double> oosPnls = allOosTrades.stream().map(Trade::pnl).toList();
        double globalOosProfitFactor = PerformanceMetrics.profitFactor(oosPnls);

        double avgIsSharpe = foldsResults.stream().mapToDouble(WfaFoldResult::isSharpe).average().orElse(0.0);
        double wfe = (avgIsSharpe != 0.0) ? (globalOosSharpe / avgIsSharpe) : 0.0;

        Strategy sampleStrategy = strategySupplier.get();

        return new WfaReport(
            wfaId,
            sampleStrategy.name(),
            config.instrument(),
            config.inSampleDays(),
            config.outOfSampleDays(),
            config.anchored(),
            config.initialCapital(),
            sanitizeDouble(wfe),
            sanitizeDouble(globalOosSharpe),
            sanitizeDouble(globalOosMaxDrawdownPct),
            sanitizeDouble(globalOosProfitFactor),
            sanitizeDouble(globalOosReturnPct),
            allOosTrades.size(),
            foldsResults,
            allOosTrades
        );
    }

    private List<WfaFold> generateFolds() {
        if (bars.isEmpty()) {
            return List.of();
        }
        Instant tStart = bars.getFirst().timestamp();
        Instant tEnd = bars.getLast().timestamp();

        List<WfaFold> folds = new ArrayList<>();
        int index = 0;
        while (true) {
            Instant isStart;
            Instant isEnd;
            java.time.ZonedDateTime zStart = tStart.atZone(java.time.ZoneOffset.UTC);
            java.time.ZonedDateTime zIsStart;
            java.time.ZonedDateTime zIsEnd;
            if (config.anchored()) {
                zIsStart = zStart;
                zIsEnd = zStart.plusDays(config.inSampleDays() + (long) index * config.outOfSampleDays());
            } else {
                zIsStart = zStart.plusDays((long) index * config.outOfSampleDays());
                zIsEnd = zIsStart.plusDays(config.inSampleDays());
            }
            java.time.ZonedDateTime zOosStart = zIsEnd;
            java.time.ZonedDateTime zOosEnd = zOosStart.plusDays(config.outOfSampleDays());

            isStart = zIsStart.toInstant();
            isEnd = zIsEnd.toInstant();
            Instant oosStart = zOosStart.toInstant();
            Instant oosEnd = zOosEnd.toInstant();

            if (!oosStart.isBefore(tEnd)) {
                break;
            }

            if (oosEnd.isAfter(tEnd)) {
                log.warn("Fold {} OOS period is truncated to data end. Required: {}, Actual end: {}", index, oosEnd, tEnd);
                oosEnd = tEnd;
                folds.add(new WfaFold(index, isStart, isEnd, oosStart, oosEnd));
                break;
            }

            folds.add(new WfaFold(index, isStart, isEnd, oosStart, oosEnd));
            index++;
        }
        return folds;
    }

    private EvaluationResult runGridSearch(List<Bar> isBars, List<Map<String, Double>> combinations) {
        List<CompletableFuture<EvaluationResult>> futures = new ArrayList<>();
        for (Map<String, Double> combination : combinations) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                Strategy strat = strategySupplier.get();
                applyParameters(strat, combination);

                BacktestEngine engine = new BacktestEngine(strat, isBars, config.initialCapital())
                    .withCommissionFixed(commissionFixed)
                    .withCommissionPct(commissionPct)
                    .withSlippageFixed(slippageFixed)
                    .withSlippagePct(slippagePct)
                    .withStopSlippagePct(stopSlippagePct)
                    .withUsdJpyRate(usdJpyRate);

                BacktestResult result = engine.run();
                return new EvaluationResult(combination, result.sharpeRatio(), result.totalTrades(), result.totalReturnPct(), result.maxDrawdownPct());
            }, executor));
        }

        int successCount = 0;
        EvaluationResult best = null;
        Throwable lastError = null;
        for (CompletableFuture<EvaluationResult> f : futures) {
            try {
                EvaluationResult res = f.join();
                if (res == null) continue;
                successCount++;
                if (best == null) {
                    best = res;
                } else {
                    int cmp = Double.compare(res.sharpeRatio, best.sharpeRatio);
                    if (cmp > 0) {
                        best = res;
                    } else if (cmp == 0) {
                        if (res.tradesCount > best.tradesCount) {
                            best = res;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error evaluating parameter combination", e);
                lastError = e;
            }
        }
        if (successCount == 0 && !combinations.isEmpty()) {
            throw new RuntimeException("100% of grid search backtest combinations failed", lastError);
        }
        return best;
    }

    @Override
    public void close() {
        log.info("Shutting down WfaEngine executor pool");
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("WfaEngine executor pool did not terminate within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------
    //  Helper structures & algorithms
    // ---------------------------------------------------------------

    private static class EvaluationResult {
        final Map<String, Double> combination;
        final double sharpeRatio;
        final int tradesCount;
        final double returnPct;
        final double maxDrawdownPct;

        EvaluationResult(Map<String, Double> combination, double sharpeRatio, int tradesCount, double returnPct, double maxDrawdownPct) {
            this.combination = combination;
            this.sharpeRatio = Double.isNaN(sharpeRatio) ? 0.0 : sharpeRatio;
            this.tradesCount = tradesCount;
            this.returnPct = returnPct;
            this.maxDrawdownPct = maxDrawdownPct;
        }
    }

    public static List<Bar> filterBars(List<Bar> bars, Instant start, Instant end) {
        return filterBars(bars, start, end, true);
    }

    public static List<Bar> filterBars(List<Bar> bars, Instant start, Instant end, boolean inclusiveEnd) {
        return bars.stream()
            .filter(b -> {
                Instant ts = b.timestamp();
                if (ts.isBefore(start)) {
                    return false;
                }
                if (inclusiveEnd) {
                    return !ts.isAfter(end);
                } else {
                    return ts.isBefore(end);
                }
            })
            .toList();
    }

    private static List<Map<String, Double>> generateCombinations(List<ParameterRange> ranges) {
        long totalCombinations = 1;
        for (ParameterRange range : ranges) {
            if (range.step() <= 0) {
                throw new IllegalArgumentException("Parameter step must be positive for " + range.name());
            }
            double span = range.max() - range.min();
            long steps = Math.round(span / range.step()) + 1;
            if (steps <= 0) {
                throw new IllegalArgumentException("Invalid steps calculated for " + range.name());
            }
            if (totalCombinations > 0 && steps > Long.MAX_VALUE / totalCombinations) {
                throw new IllegalArgumentException("WFA parameter combinations search space is too large (overflow)");
            }
            totalCombinations *= steps;
            if (totalCombinations > 100000 || totalCombinations < 0) {
                throw new IllegalArgumentException("WFA parameter combinations search space is too large: expected " + totalCombinations + " (limit is 100,000)");
            }
        }

        List<Map<String, Double>> result = new ArrayList<>();
        generateCombinationsHelper(ranges, 0, new LinkedHashMap<>(), result);
        return result;
    }

    private static void generateCombinationsHelper(
        List<ParameterRange> ranges,
        int index,
        Map<String, Double> current,
        List<Map<String, Double>> result
    ) {
        if (index == ranges.size()) {
            result.add(new LinkedHashMap<>(current));
            return;
        }
        ParameterRange range = ranges.get(index);
        double min = range.min();
        double max = range.max();
        double step = range.step();
        double span = max - min;
        long stepsCount = Math.round(span / step) + 1;

        for (long i = 0; i < stepsCount; i++) {
            double val = min + i * step;
            if (i == stepsCount - 1) {
                val = max;
            }
            current.put(range.name(), val);
            generateCombinationsHelper(ranges, index + 1, current, result);
        }
    }

    private static void applyParameters(Strategy strategy, Map<String, Double> params) {
        Strategy target = strategy;
        while (target != null) {
            java.lang.reflect.Field delegateField = getFieldCached(target.getClass(), "delegate");
            if (delegateField != null) {
                try {
                    delegateField.setAccessible(true);
                    Object val = delegateField.get(target);
                    if (val instanceof Strategy) {
                        target = (Strategy) val;
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("Failed to unwrap delegate from strategy {}", target.getClass().getName(), e);
                }
            }
            break;
        }
        if (target == null) {
            throw new IllegalArgumentException("Strategy target resolved to null");
        }
        Class<?> clazz = target.getClass();
        for (Map.Entry<String, Double> entry : params.entrySet()) {
            String name = entry.getKey();
            double val = entry.getValue();
            if (Double.isNaN(val) || Double.isInfinite(val)) {
                throw new IllegalArgumentException("Parameter " + name + " cannot be NaN or Infinite");
            }
            try {
                java.lang.reflect.Field field = getFieldCached(clazz, name);
                if (field == null) {
                    throw new IllegalArgumentException("Field " + name + " not found on strategy " + clazz.getName());
                }
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalArgumentException("Field " + name + " is static and cannot be modified on strategy " + clazz.getName());
                }
                if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                    throw new IllegalArgumentException("Field " + name + " is final and cannot be modified on strategy " + clazz.getName());
                }
                Class<?> type = field.getType();
                if (type == int.class || type == Integer.class) {
                    long rounded = Math.round(val);
                    if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Value " + val + " for field " + name + " exceeds integer bounds");
                    }
                    field.set(target, (int) rounded);
                } else if (type == double.class || type == Double.class) {
                    field.set(target, val);
                } else if (type == long.class || type == Long.class) {
                    field.set(target, Math.round(val));
                } else if (type == float.class || type == Float.class) {
                    if (val < -Float.MAX_VALUE || val > Float.MAX_VALUE) {
                        throw new IllegalArgumentException("Value " + val + " for field " + name + " exceeds float bounds");
                    }
                    field.set(target, (float) val);
                } else {
                    throw new IllegalArgumentException("Unsupported field type: " + type.getName() + " for field " + name);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to set parameter " + name + " to value " + val + " on strategy " + clazz.getName(), e);
            }
        }
    }

    private static void syncCrossingTradesToStrategy(Strategy strategy, List<Trade> activeCrossingTrades, Bar bar) {
        Strategy target = strategy;
        while (target != null) {
            java.lang.reflect.Field delegateField = getFieldCached(target.getClass(), "delegate");
            if (delegateField != null) {
                try {
                    Object val = delegateField.get(target);
                    if (val instanceof Strategy) {
                        target = (Strategy) val;
                        continue;
                    }
                } catch (Exception e) {}
            }
            break;
        }
        if (target == null) return;
        
        Class<?> clazz = target.getClass();
        java.lang.reflect.Field activeSideField = getFieldCached(clazz, "activeSide");
        if (activeSideField != null) {
            Trade crossingTrade = null;
            for (Trade t : activeCrossingTrades) {
                if (t.symbol().equals(bar.symbol())) {
                    crossingTrade = t;
                    break;
                }
            }
            try {
                if (crossingTrade != null) {
                    Object enumVal = null;
                    Class<?> enumClass = activeSideField.getType();
                    if (enumClass.isEnum()) {
                        String enumName = crossingTrade.side() == Order.Side.BUY ? "LONG" : "SHORT";
                        for (Object enumConstant : enumClass.getEnumConstants()) {
                            if (enumConstant.toString().equals(enumName)) {
                                enumVal = enumConstant;
                                break;
                            }
                        }
                    }
                    activeSideField.set(target, enumVal);
                    
                    java.lang.reflect.Field activeSlField = getFieldCached(clazz, "activeSl");
                    if (activeSlField != null) {
                        activeSlField.set(target, crossingTrade.stopLoss());
                    }
                    java.lang.reflect.Field activeTpField = getFieldCached(clazz, "activeTp");
                    if (activeTpField != null) {
                        activeTpField.set(target, crossingTrade.takeProfit());
                    }
                    java.lang.reflect.Field barsInTradeField = getFieldCached(clazz, "barsInTrade");
                    if (barsInTradeField != null) {
                        barsInTradeField.set(target, 0);
                    }
                } else {
                    activeSideField.set(target, null);
                    java.lang.reflect.Field activeSlField = getFieldCached(clazz, "activeSl");
                    if (activeSlField != null) {
                        activeSlField.set(target, 0.0);
                    }
                    java.lang.reflect.Field activeTpField = getFieldCached(clazz, "activeTp");
                    if (activeTpField != null) {
                        activeTpField.set(target, 0.0);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to synchronize crossing trade to strategy {}", clazz.getName(), e);
            }
        }
    }

    private static java.lang.reflect.Field getFieldCached(Class<?> clazz, String name) {
        String key = clazz.getName() + "#" + name;
        return fieldCache.computeIfAbsent(key, k -> {
            Class<?> current = clazz;
            while (current != null) {
                try {
                    java.lang.reflect.Field field = current.getDeclaredField(name);
                    try {
                        field.setAccessible(true);
                        return field;
                    } catch (Exception e) {
                        log.warn("Field {} in {} is not accessible", name, current.getName(), e);
                    }
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            return ABSENT_FIELD;
        }) == ABSENT_FIELD ? null : fieldCache.get(key);
    }

    private double computeTradeContribution(Trade t, Bar bar) {
        Instant ts = bar.timestamp();
        if (ts.isBefore(t.entryTime())) {
            return 0.0;
        }
        
        double entryComm = commissionFixed + (t.entryPrice() * t.quantity() * commissionPct);
        
        if (ts.isBefore(t.exitTime())) {
            // Trade is open
            double floatingPnl = ForexPnL.pnlUsd(t.symbol(), t.side(), t.entryPrice(), bar.close(), t.quantity(), usdJpyRate);
            return -entryComm + floatingPnl;
        } else {
            // Trade is closed
            double exitComm = commissionFixed + (t.exitPrice() * t.quantity() * commissionPct);
            return -entryComm - exitComm + t.pnl();
        }
    }

    private static double sanitizeDouble(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) {
            return 0.0;
        }
        return val;
    }
}
