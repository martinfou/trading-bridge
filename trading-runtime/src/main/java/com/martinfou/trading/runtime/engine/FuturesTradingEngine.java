package com.martinfou.trading.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.FillMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.TimeConventions;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.runtime.ibkr.IbkrLiveRunner;
import com.martinfou.trading.runtime.ibkr.SqStrategyEvaluator;
import com.martinfou.trading.strategies.LiveStrategyRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Config-driven Futures Trading Engine (PRD §5 F1, F2, F3, F4, F5).
 *
 * <p>Reads a single {@link EngineRunConfig} ({@code config/runs/<strategy>-<symbol>-<mode>.json}),
 * validates strategy, symbol, mode, broker, and cost-validation gate (F5), and executes either:</p>
 * <ul>
 *   <li>{@code backtest}: loads local bars ({@code .bars} or CSV), drives {@link BacktestEngine}
 *       unchanged, emits unified {@link FillLogRecord} JSONL records, and writes a JSON + Markdown report.</li>
 *   <li>{@code paper}: delegates to {@link IbkrLiveRunner} with the unified JSONL fill logger attached.</li>
 *   <li>{@code report}: prints side-by-side median slippage and commission comparison across modes (F4).</li>
 * </ul>
 */
public final class FuturesTradingEngine {

    private static final Logger log = LoggerFactory.getLogger(FuturesTradingEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Set<String> VALID_MODES = Set.of("backtest", "paper", "live");
    public static final Set<String> VALID_BROKERS = Set.of("ibkr", "oanda");
    public static final Path DEFAULT_REPORTS_DIR = Paths.get("data-futures/reports");

    public record BacktestRunOutcome(
        EngineRunConfig config,
        BacktestResult result,
        Path fillLogPath,
        Path reportPath,
        int fillCount
    ) {}

    private FuturesTradingEngine() {}

    /**
     * Validates an {@link EngineRunConfig} against all F1 and F5 rules.
     * Throws {@link IllegalArgumentException} naming the valid values when any check fails.
     */
    @SuppressWarnings("unchecked")
    public static void validateConfig(EngineRunConfig cfg) {
        Objects.requireNonNull(cfg, "EngineRunConfig is required");

        // 1. Validate mode
        String mode = cfg.mode() != null ? cfg.mode().trim().toLowerCase(Locale.ROOT) : "";
        if (!VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                "Invalid mode '" + cfg.mode() + "'. Valid modes: " + VALID_MODES);
        }

        // 2. Validate broker
        String broker = cfg.broker() != null ? cfg.broker().trim().toLowerCase(Locale.ROOT) : "";
        if (!VALID_BROKERS.contains(broker)) {
            throw new IllegalArgumentException(
                "Invalid broker '" + cfg.broker() + "'. Valid brokers: " + VALID_BROKERS);
        }

        // 3. Validate symbol in FuturesRegistry
        String symbol = cfg.symbol() != null ? cfg.symbol().trim().toUpperCase(Locale.ROOT) : "";
        if (symbol.isEmpty() || !FuturesRegistry.isFutures(symbol)) {
            throw new IllegalArgumentException(
                "Unknown futures symbol '" + cfg.symbol() + "'. Valid symbols: " + FuturesRegistry.all().keySet());
        }

        // 4. Validate strategy in LiveStrategyRunner.getStrategyMap()
        Map<String, Class<? extends Strategy>> strategyMap = loadStrategyMap();
        String strategyKey = cfg.strategy() != null ? cfg.strategy().trim() : "";
        if (strategyKey.isEmpty() || !strategyMap.containsKey(strategyKey)) {
            throw new IllegalArgumentException(
                "Unknown strategy '" + cfg.strategy() + "'. Valid strategies: " + strategyMap.keySet());
        }

        // 5. Validate timeframe & capital & costs
        if (cfg.timeframe() == null || cfg.timeframe().isBlank()) {
            throw new IllegalArgumentException("timeframe is required (e.g. M15, M30, H1)");
        }
        if (cfg.capital() <= 0.0 || !Double.isFinite(cfg.capital())) {
            throw new IllegalArgumentException("capital must be > 0, got: " + cfg.capital());
        }
        if (cfg.costs() == null) {
            throw new IllegalArgumentException("costs block is required (commissionPerContract, slippageFixed)");
        }
        if (cfg.costs().commissionPerContract() < 0.0 || cfg.costs().slippageFixed() < 0.0
            || !Double.isFinite(cfg.costs().commissionPerContract())
            || !Double.isFinite(cfg.costs().slippageFixed())) {
            throw new IllegalArgumentException("costs.commissionPerContract and costs.slippageFixed must be finite and >= 0");
        }
        parseFillMode(cfg.costs().fillMode());

        // 6. F5 — Cost validation gate for paper / live
        boolean hasValidatedBy = cfg.validatedBy() != null && !cfg.validatedBy().isBlank();
        if ("live".equals(mode)) {
            if (cfg.unvalidated() || !hasValidatedBy) {
                throw new IllegalArgumentException(
                    "Refusing to run in 'live' mode (F5): strategy '" + strategyKey
                    + "' is unvalidated (missing 'validatedBy' backtest reference or 'unvalidated: true' is set). "
                    + "Unvalidated runs are strictly refused on live.");
            }
            if (!cfg.liveAccountEnabled()) {
                throw new IllegalArgumentException(
                    "Refusing to run in 'live' mode (F1/F3): broker account is not explicitly marked live-capable "
                    + "(liveAccountEnabled=false). Live money is disabled in this release.");
            }
        } else if ("paper".equals(mode)) {
            if (!hasValidatedBy && !cfg.unvalidated()) {
                throw new IllegalArgumentException(
                    "Refusing to run in 'paper' mode (F5): config must either name the validating backtest "
                    + "in 'validatedBy' or explicitly set 'unvalidated: true'.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Class<? extends Strategy>> loadStrategyMap() {
        try {
            Method m = LiveStrategyRunner.class.getDeclaredMethod("getStrategyMap");
            m.setAccessible(true);
            return (Map<String, Class<? extends Strategy>>) m.invoke(null);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to inspect LiveStrategyRunner.getStrategyMap()", e);
        }
    }

    /**
     * Executes a backtest run from {@link EngineRunConfig}, reusing {@link BacktestEngine} unchanged (F2),
     * writing all fills to the unified JSONL fill log (F4), and writing a JSON summary report.
     */
    public static BacktestRunOutcome runBacktest(
        EngineRunConfig cfg,
        Path dataRootDir,
        Path fillsDir,
        Path reportsDir
    ) throws IOException {
        validateConfig(cfg);
        if (!"backtest".equalsIgnoreCase(cfg.mode())) {
            throw new IllegalArgumentException("runBacktest requires mode='backtest', got: " + cfg.mode());
        }

        List<Bar> bars = loadBarsForBacktest(cfg, dataRootDir);
        if (bars.isEmpty()) {
            throw new IllegalStateException("No historical bars loaded for " + cfg.symbol() + " " + cfg.timeframe());
        }

        Strategy strategy = SqStrategyEvaluator.instantiateStrategy(cfg.strategy(), cfg.symbol());
        FillMode fillMode = parseFillMode(cfg.costs().fillMode());

        BacktestEngine engine = new BacktestEngine(strategy, bars, cfg.capital())
            .withCommissionFixed(cfg.costs().commissionPerContract())
            .withSlippageFixed(cfg.costs().slippageFixed())
            .withFillMode(fillMode)
            .withMarginTracking(cfg.costs().isMarginTrackingEnabled())
            .withRollover(cfg.costs().isRolloverEnabled());

        BacktestResult result = engine.run();

        // Emit unified fill log records (F4): one entry fill + one exit fill per completed trade
        FillLogWriter fillWriter = FillLogWriter.forRun(fillsDir, cfg.strategy(), cfg.symbol(), "backtest");
        fillWriter.truncate();
        int fillCount = emitBacktestFills(cfg, result, fillWriter);

        // Write backtest report
        Path reportPath = writeBacktestReport(cfg, result, fillWriter.filePath(), reportsDir);
        return new BacktestRunOutcome(cfg, result, fillWriter.filePath(), reportPath, fillCount);
    }

    static int emitBacktestFills(EngineRunConfig cfg, BacktestResult result, FillLogWriter writer) {
        double slipFixed = cfg.costs().slippageFixed();
        double commPerContract = cfg.costs().commissionPerContract();
        int count = 0;

        for (Trade trade : result.trades()) {
            double qty = trade.quantity();
            Order.Side entrySide = trade.side();
            Order.Side exitSide = entrySide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;

            // Entry fill: BacktestEngine applied slipFixed against the trader on entry
            double entryFilled = trade.entryPrice();
            double entryRequested = FuturesRegistry.quantizePrice(
                cfg.symbol(),
                entrySide == Order.Side.BUY ? entryFilled - slipFixed : entryFilled + slipFixed
            );
            double entrySlippage = FillLogRecord.computeSlippage(entrySide.name(), entryRequested, entryFilled);
            double entryCommission = Math.round(commPerContract * qty * 10_000.0) / 10_000.0;

            writer.append(new FillLogRecord(
                cfg.strategy(),
                cfg.symbol(),
                entrySide.name(),
                qty,
                entryRequested,
                entryFilled,
                entrySlippage,
                entryCommission,
                trade.entryTime().toString(),
                "backtest"
            ));
            count++;

            // Exit fill (SL/TP or close at end of run)
            double exitFilled = trade.exitPrice();
            double exitRequested = exitFilled;
            double exitSlippage = FillLogRecord.computeSlippage(exitSide.name(), exitRequested, exitFilled);
            // BacktestEngine deducts commissionFixed once per executeFill (on entry order fill);
            // SL/TP exits via checkStopLossesTakeProfits have 0 additional commission in BacktestEngine.
            double exitCommission = 0.0;

            writer.append(new FillLogRecord(
                cfg.strategy(),
                cfg.symbol(),
                exitSide.name(),
                qty,
                exitRequested,
                exitFilled,
                exitSlippage,
                exitCommission,
                trade.exitTime().toString(),
                "backtest"
            ));
            count++;
        }
        return count;
    }

    public static List<Bar> loadBarsForBacktest(EngineRunConfig cfg, Path dataRootDir) throws IOException {
        Path root = dataRootDir != null ? dataRootDir : Paths.get(".");
        String symbol = cfg.symbol().trim().toUpperCase(Locale.ROOT);
        String tf = cfg.timeframe().trim().toUpperCase(Locale.ROOT);
        EngineRunConfig.PeriodSpec period = cfg.period();

        if (period != null && period.filePath() != null && !period.filePath().isBlank()) {
            Path explicit = root.resolve(period.filePath().trim());
            return filterByPeriod(loadFileAuto(explicit, symbol), period);
        }

        String dataSource = (period != null && period.dataSource() != null && !period.dataSource().isBlank())
            ? period.dataSource().trim().toLowerCase(Locale.ROOT)
            : "bars";

        if ("csv".equals(dataSource)) {
            int year = resolveEndYear(period, 2026);
            Path csvPath = root.resolve("data/historical/csv/" + symbol + "_" + tf + "_" + year + ".csv");
            return filterByPeriod(loadIbkrOrStandardCsv(csvPath, symbol), period);
        }

        // Default per Decision D11: authoritative .bars files under data/historical/bars
        Path barsDir = root.resolve("data/historical/bars");
        int startYear = resolveStartYear(period, 2026);
        int endYear = resolveEndYear(period, 2026);
        List<Bar> loaded = HistoricalDataLoader.loadYearRange(symbol, startYear, endYear, tf, barsDir);
        return filterByPeriod(loaded, period);
    }

    public static List<Bar> loadFileAuto(Path path, String symbol) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return loadIbkrOrStandardCsv(path, symbol);
        }
        return HistoricalDataLoader.loadPath(path, symbol);
    }

    /**
     * Loads an IBKR-exported CSV (with {@code yyyyMMdd HH:mm:ss US/Eastern} timestamps, matching
     * {@code RunMgcGoldBreakoutBacktest.loadIbkrCsv}) or falls back to {@link HistoricalDataLoader#loadPath}.
     */
    public static List<Bar> loadIbkrOrStandardCsv(Path path, String symbol) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("CSV file not found: " + path);
        }
        ZoneId eastern = ZoneId.of("America/New_York");
        DateTimeFormatter ibkrFmt = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) {
                return List.of();
            }
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 6) {
                    continue;
                }
                String rawDate = p[0].trim();
                Instant instant;
                if (rawDate.contains("US/Eastern") || rawDate.matches("\\d{8}\\s+\\d{2}:\\d{2}:\\d{2}.*")) {
                    String cleanDate = rawDate.replace(" US/Eastern", "").trim().replaceAll("\\s+", " ");
                    LocalDateTime ldt = LocalDateTime.parse(cleanDate, ibkrFmt);
                    instant = ldt.atZone(eastern).toInstant();
                } else {
                    instant = TimeConventions.parseCsvTimestamp(rawDate);
                }
                double open = Double.parseDouble(p[1].trim());
                double high = Double.parseDouble(p[2].trim());
                double low = Double.parseDouble(p[3].trim());
                double close = Double.parseDouble(p[4].trim());
                long volume = Long.parseLong(p[5].trim());
                bars.add(new Bar(symbol, instant, open, high, low, close, volume));
            }
        }
        return bars;
    }

    private static List<Bar> filterByPeriod(List<Bar> bars, EngineRunConfig.PeriodSpec period) {
        if (period == null || bars.isEmpty()) {
            return bars;
        }
        Instant startInstant = parseBoundaryStart(period.start());
        Instant endInstant = parseBoundaryEnd(period.end());
        if (startInstant == null && endInstant == null) {
            return bars;
        }
        List<Bar> filtered = new ArrayList<>(bars.size());
        for (Bar b : bars) {
            if (startInstant != null && b.timestamp().isBefore(startInstant)) {
                continue;
            }
            if (endInstant != null && b.timestamp().isAfter(endInstant)) {
                continue;
            }
            filtered.add(b);
        }
        return filtered;
    }

    private static Instant parseBoundaryStart(String s) {
        if (s == null || s.isBlank() || s.matches("\\d{4}")) {
            return null;
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.parse(s.trim());
    }

    private static Instant parseBoundaryEnd(String s) {
        if (s == null || s.isBlank() || s.matches("\\d{4}")) {
            return null;
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(s).plusDays(1).atStartOfDay(ZoneOffset.UTC).minusNanos(1).toInstant();
        }
        return Instant.parse(s.trim());
    }

    private static int resolveStartYear(EngineRunConfig.PeriodSpec period, int defaultYear) {
        if (period == null || period.start() == null || period.start().isBlank()) {
            return defaultYear;
        }
        String s = period.start().trim();
        return Integer.parseInt(s.substring(0, 4));
    }

    private static int resolveEndYear(EngineRunConfig.PeriodSpec period, int defaultYear) {
        if (period == null || period.end() == null || period.end().isBlank()) {
            return resolveStartYear(period, defaultYear);
        }
        String s = period.end().trim();
        return Integer.parseInt(s.substring(0, 4));
    }

    private static FillMode parseFillMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return FillMode.TOUCH;
        }
        try {
            return FillMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid costs.fillMode '" + raw + "'. Valid fillModes: " + java.util.Arrays.toString(FillMode.values()), e);
        }
    }

    private static Path writeBacktestReport(
        EngineRunConfig cfg,
        BacktestResult r,
        Path fillLogPath,
        Path reportsDir
    ) throws IOException {
        Path dir = reportsDir != null ? reportsDir : DEFAULT_REPORTS_DIR;
        Files.createDirectories(dir);
        Path jsonReport = dir.resolve(cfg.strategy() + "-" + cfg.symbol() + "-backtest-report.json");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("strategy", cfg.strategy());
        root.put("strategyDisplayName", r.strategyName());
        root.put("symbol", cfg.symbol());
        root.put("timeframe", cfg.timeframe());
        root.put("mode", cfg.mode());
        root.put("broker", cfg.broker());
        root.put("initialCapital", r.initialCapital());
        root.put("finalEquity", r.finalEquity());
        root.put("totalPnl", r.totalPnl());
        root.put("totalReturnPct", r.totalReturnPct());
        root.put("totalTrades", r.totalTrades());
        root.put("winningTrades", r.winningTrades());
        root.put("losingTrades", r.losingTrades());
        root.put("winRatePct", r.winRatePct());
        root.put("maxDrawdownPct", r.maxDrawdownPct());
        root.put("profitFactor", r.profitFactor());
        root.put("sharpeRatio", r.sharpeRatio());
        root.put("sortinoRatio", r.sortinoRatio());
        root.put("totalCommission", r.totalCommission());
        root.put("totalSlippage", r.totalSlippage());
        root.put("fillLogPath", fillLogPath.toString());
        root.put("generatedAt", Instant.now().toString());

        Files.writeString(jsonReport, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return jsonReport;
    }

    public static void main(String[] args) {
        try {
            int code = runCli(args, Paths.get("."), FillLogWriter.DEFAULT_FILLS_DIR, DEFAULT_REPORTS_DIR);
            if (code != 0) {
                System.exit(code);
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            log.error("FuturesTradingEngine failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    public static int runCli(String[] args, Path dataRootDir, Path fillsDir, Path reportsDir) throws Exception {
        if (args == null || args.length == 0) {
            System.err.println("Usage: FuturesTradingEngine <config/runs/file.json> | --report <strategy> <symbol> [baselineMode] [targetMode]");
            return 1;
        }
        if ("--report".equals(args[0])) {
            if (args.length < 3) {
                System.err.println("Usage: FuturesTradingEngine --report <strategy> <symbol> [baselineMode] [targetMode]");
                return 1;
            }
            String strategy = args[1];
            String symbol = args[2];
            String baseMode = args.length >= 4 ? args[3] : "backtest";
            String targetMode = args.length >= 5 ? args[4] : "paper";
            FillLogComparisonReport.ComparisonResult comparison =
                FillLogComparisonReport.compare(fillsDir, strategy, symbol, baseMode, targetMode);
            System.out.print(comparison.formatTable());
            return 0;
        }

        Path configPath = Paths.get(args[0]);
        EngineRunConfig cfg = EngineRunConfig.load(configPath);
        validateConfig(cfg);

        String mode = cfg.mode().trim().toLowerCase(Locale.ROOT);
        if ("backtest".equals(mode)) {
            BacktestRunOutcome outcome = runBacktest(cfg, dataRootDir, fillsDir, reportsDir);
            BacktestResult r = outcome.result();
            System.out.printf(Locale.US,
                "BACKTEST COMPLETE [%s / %s %s]: trades=%d finalEquity=$%.2f netPnl=$%.2f (%.2f%%) maxDD=%.2f%% PF=%.2f report=%s fills=%s%n",
                cfg.strategy(), cfg.symbol(), cfg.timeframe(),
                r.totalTrades(), r.finalEquity(), r.totalPnl(), r.totalReturnPct(),
                r.maxDrawdownPct(), r.profitFactor(),
                outcome.reportPath(), outcome.fillLogPath());
            return 0;
        } else if ("paper".equals(mode)) {
            IbkrLiveRunner.main(new String[0]);
            return 0;
        }
        return 1;
    }
}

