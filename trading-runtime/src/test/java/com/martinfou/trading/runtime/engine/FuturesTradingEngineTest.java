package com.martinfou.trading.runtime.engine;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.FillMode;
import com.martinfou.trading.broker.AccountState;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEvent;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.runtime.ibkr.IbkrLiveRunner;
import com.martinfou.trading.strategies.sqimported.Strategy_Gold_Breakout_M30;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance & parity tests for the Futures Trading Engine (PRD §5 F1–F5 & §9 A1/A2).
 */
class FuturesTradingEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void f1_rejectsUnknownModeStrategyAndSymbolWithValidValuesInMessage() {
        EngineRunConfig.CostsSpec costs = new EngineRunConfig.CostsSpec(0.62, 0.10, "TOUCH", false, true);

        // Invalid mode
        EngineRunConfig badMode = new EngineRunConfig(
            "sq-gold-m30", "MGC", "M30", "simulation", "ibkr",
            null, null, 11270.0, costs, null, true, false
        );
        IllegalArgumentException modeEx = assertThrows(
            IllegalArgumentException.class,
            () -> FuturesTradingEngine.validateConfig(badMode)
        );
        assertTrue(modeEx.getMessage().contains("backtest"), "Should list valid modes: " + modeEx.getMessage());
        assertTrue(modeEx.getMessage().contains("paper"), "Should list valid modes: " + modeEx.getMessage());

        // Unknown symbol
        EngineRunConfig badSymbol = new EngineRunConfig(
            "sq-gold-m30", "UNKNOWN_FUT", "M30", "backtest", "ibkr",
            null, null, 11270.0, costs, null, false, false
        );
        IllegalArgumentException symEx = assertThrows(
            IllegalArgumentException.class,
            () -> FuturesTradingEngine.validateConfig(badSymbol)
        );
        assertTrue(symEx.getMessage().contains("MGC"), "Should list valid symbols: " + symEx.getMessage());

        // Unknown strategy
        EngineRunConfig badStrategy = new EngineRunConfig(
            "nonexistent-strategy-xyz", "MGC", "M30", "backtest", "ibkr",
            null, null, 11270.0, costs, null, false, false
        );
        IllegalArgumentException stratEx = assertThrows(
            IllegalArgumentException.class,
            () -> FuturesTradingEngine.validateConfig(badStrategy)
        );
        assertTrue(stratEx.getMessage().contains("sq-gold-m30"), "Should list valid strategies: " + stratEx.getMessage());
    }

    @Test
    void f5_refusesUnvalidatedStrategyOnLiveAndRequiresExplicitValidationOrFlagOnPaper() {
        EngineRunConfig.CostsSpec costs = new EngineRunConfig.CostsSpec(0.62, 0.10, "TOUCH", false, true);

        // 1. Live mode with unvalidated=true -> refused
        EngineRunConfig liveUnvalidated = new EngineRunConfig(
            "sq-mgc-m30-breakout-0-24542", "MGC", "M30", "live", "ibkr",
            null, null, 11270.0, costs, "report.json", true, true
        );
        IllegalArgumentException ex1 = assertThrows(
            IllegalArgumentException.class,
            () -> FuturesTradingEngine.validateConfig(liveUnvalidated)
        );
        assertTrue(ex1.getMessage().contains("F5"), ex1.getMessage());

        // 2. Live mode with missing validatedBy -> refused
        EngineRunConfig liveMissingValidation = new EngineRunConfig(
            "sq-mgc-m30-breakout-0-24542", "MGC", "M30", "live", "ibkr",
            null, null, 11270.0, costs, "", false, true
        );
        IllegalArgumentException ex2 = assertThrows(
            IllegalArgumentException.class,
            () -> FuturesTradingEngine.validateConfig(liveMissingValidation)
        );
        assertTrue(ex2.getMessage().contains("validatedBy"), ex2.getMessage());

        // 3. Live mode without liveAccountEnabled -> refused (F1/F3)
        EngineRunConfig liveAccountDisabled = new EngineRunConfig(
            "sq-mgc-m30-breakout-0-24542", "MGC", "M30", "live", "ibkr",
            null, null, 11270.0, costs, "report.json", false, false
        );
        IllegalArgumentException ex3 = assertThrows(
            IllegalArgumentException.class,
            () -> FuturesTradingEngine.validateConfig(liveAccountDisabled)
        );
        assertTrue(ex3.getMessage().contains("liveAccountEnabled"), ex3.getMessage());

        // 4. Paper mode with neither validatedBy nor unvalidated=true -> refused
        EngineRunConfig paperNeither = new EngineRunConfig(
            "sq-mgc-m30-breakout-0-24542", "MGC", "M30", "paper", "ibkr",
            null, null, 11270.0, costs, null, false, false
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> FuturesTradingEngine.validateConfig(paperNeither)
        );

        // 5. Paper mode with unvalidated=true -> allowed
        EngineRunConfig paperExplicitUnvalidated = new EngineRunConfig(
            "sq-mgc-m30-breakout-0-24542", "MGC", "M30", "paper", "ibkr",
            null, null, 11270.0, costs, null, true, false
        );
        FuturesTradingEngine.validateConfig(paperExplicitUnvalidated);

        // 6. Paper mode with validatedBy -> allowed
        EngineRunConfig paperValidated = new EngineRunConfig(
            "sq-mgc-m30-breakout-0-24542", "MGC", "M30", "paper", "ibkr",
            null, null, 11270.0, costs, "data-futures/reports/sq-mgc-m30-breakout-0-24542-MGC-backtest-report.json", false, false
        );
        FuturesTradingEngine.validateConfig(paperValidated);
    }

    @Test
    void f2_backtestParityWithRunMgcGoldBreakoutBacktestAndThreeDistinctConfigs() throws Exception {
        Path repoRoot = Paths.get(".").toAbsolutePath().normalize();
        Path csvPath = repoRoot.resolve("data/historical/csv/MGC_M30_2026.csv");
        assertTrue(Files.exists(csvPath), "Expected MGC_M30_2026.csv on disk");

        // Direct baseline run identical to RunMgcGoldBreakoutBacktest.main()
        List<Bar> baselineBars = FuturesTradingEngine.loadIbkrOrStandardCsv(csvPath, "MGC");
        Strategy_Gold_Breakout_M30 baselineStrategy = new Strategy_Gold_Breakout_M30("MGC");
        BacktestResult baselineResult = new BacktestEngine(baselineStrategy, baselineBars, 11_270.0)
            .withCommissionFixed(0.62)
            .withSlippageFixed(0.10)
            .withFillMode(FillMode.TOUCH)
            .withMarginTracking(true)
            .run();

        Path fillsDir = tempDir.resolve("fills");
        Path reportsDir = tempDir.resolve("reports");

        // Config #1: sq-gold-m30-MGC-backtest.json (parity with RunMgcGoldBreakoutBacktest)
        EngineRunConfig cfg1 = EngineRunConfig.load(repoRoot.resolve("config/runs/sq-gold-m30-MGC-backtest.json"));
        FuturesTradingEngine.BacktestRunOutcome out1 =
            FuturesTradingEngine.runBacktest(cfg1, repoRoot, fillsDir, reportsDir);

        assertEquals(baselineResult.totalTrades(), out1.result().totalTrades(),
            "F2 parity: trade count must match RunMgcGoldBreakoutBacktest");
        assertEquals(baselineResult.finalEquity(), out1.result().finalEquity(), 1e-6,
            "F2 parity: final equity must match RunMgcGoldBreakoutBacktest");
        assertEquals(baselineResult.totalCommission(), out1.result().totalCommission(), 1e-6);
        assertEquals(baselineResult.totalSlippage(), out1.result().totalSlippage(), 1e-6);
        assertTrue(Files.exists(out1.reportPath()));
        assertTrue(Files.exists(out1.fillLogPath()));

        // Config #2: sq-mgc-m30-breakout-0-24542-MGC-backtest.json
        EngineRunConfig cfg2 = EngineRunConfig.load(repoRoot.resolve("config/runs/sq-mgc-m30-breakout-0-24542-MGC-backtest.json"));
        FuturesTradingEngine.BacktestRunOutcome out2 =
            FuturesTradingEngine.runBacktest(cfg2, repoRoot, fillsDir, reportsDir);
        assertTrue(out2.result().totalTrades() > 0, "Expected trades for sq-mgc-m30-breakout-0-24542");
        assertTrue(Files.exists(out2.reportPath()));
        assertTrue(Files.exists(out2.fillLogPath()));

        // Config #3: sq-mgc-m30-breakout-0-32921-MGC-backtest.json
        EngineRunConfig cfg3 = EngineRunConfig.load(repoRoot.resolve("config/runs/sq-mgc-m30-breakout-0-32921-MGC-backtest.json"));
        FuturesTradingEngine.BacktestRunOutcome out3 =
            FuturesTradingEngine.runBacktest(cfg3, repoRoot, fillsDir, reportsDir);
        assertTrue(out3.result().totalTrades() > 0, "Expected trades for sq-mgc-m30-breakout-0-32921");
        assertTrue(Files.exists(out3.reportPath()));
        assertTrue(Files.exists(out3.fillLogPath()));

        // Config #4: sq-mgc-m30-breakout-0-24542-MGC-paper.json validates cleanly
        EngineRunConfig cfg4 = EngineRunConfig.load(repoRoot.resolve("config/runs/sq-mgc-m30-breakout-0-24542-MGC-paper.json"));
        FuturesTradingEngine.validateConfig(cfg4);
    }

    @Test
    void f4_unifiedFillLogSchemaInBacktestAndPaperPlusComparisonReport() throws Exception {
        Path fillsDir = tempDir.resolve("fills");
        Path reportsDir = tempDir.resolve("reports");
        Path repoRoot = Paths.get(".").toAbsolutePath().normalize();

        // 1. Run backtest to emit backtest JSONL fills
        EngineRunConfig btCfg = EngineRunConfig.load(repoRoot.resolve("config/runs/sq-gold-m30-MGC-backtest.json"));
        FuturesTradingEngine.BacktestRunOutcome btOutcome =
            FuturesTradingEngine.runBacktest(btCfg, repoRoot, fillsDir, reportsDir);
        List<FillLogRecord> btFills = FillLogWriter.readAll(btOutcome.fillLogPath());
        assertFalse(btFills.isEmpty(), "Backtest should emit fill log records");
        for (FillLogRecord rec : btFills) {
            assertEquals("sq-gold-m30", rec.strategy());
            assertEquals("MGC", rec.symbol());
            assertEquals("backtest", rec.mode());
            assertTrue(rec.filledPrice() > 0.0);
            assertNotNull(rec.timestamp());
        }

        // 2. Simulate paper fills via IbkrLiveRunner's BrokerEvent listener into the same fillsDir
        FillLogWriter paperWriter = FillLogWriter.forRun(fillsDir, "sq-gold-m30", "MGC", "paper");
        CapturingBroker fakeBroker = new CapturingBroker();
        IbkrLiveRunner.RunnerConfig runnerCfg = new IbkrLiveRunner.RunnerConfig(
            "127.0.0.1", 4002, "DU1234567", 11, "MGC", "30 mins", 30,
            "sq-gold-m30", "1", tempDir.resolve("state"), false
        );
        Clock fixedClock = Clock.fixed(Instant.parse("2026-10-29T14:00:05Z"), ZoneOffset.UTC);
        try (IbkrLiveRunner runner = new IbkrLiveRunner(
            runnerCfg,
            fakeBroker,
            sym -> List.of(),
            () -> {},
            fixedClock,
            paperWriter,
            0.62
        )) {
            runner.start();
            Order order = new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 2750.0)
                .withStopLoss(2730.0)
                .withStrategyId("sq-gold-m30_MGC");
            fakeBroker.fireEvent(BrokerEvent.submitted(order));
            fakeBroker.fireEvent(BrokerEvent.fill(order, 2750.20));
        }

        List<FillLogRecord> paperFills = FillLogWriter.readAll(paperWriter.filePath());
        assertEquals(1, paperFills.size());
        FillLogRecord pf = paperFills.get(0);
        assertEquals("sq-gold-m30", pf.strategy());
        assertEquals("MGC", pf.symbol());
        assertEquals("BUY", pf.side());
        assertEquals(1.0, pf.quantity(), 1e-9);
        assertEquals(2750.0, pf.requestedPrice(), 1e-6);
        assertEquals(2750.20, pf.filledPrice(), 1e-6);
        assertEquals(0.20, pf.computedSlippage(), 1e-6);
        assertEquals(0.62, pf.commission(), 1e-6);
        assertEquals("paper", pf.mode());

        // 3. Compare side-by-side via FillLogComparisonReport and CLI --report
        FillLogComparisonReport.ComparisonResult comparison =
            FillLogComparisonReport.compare(fillsDir, "sq-gold-m30", "MGC", "backtest", "paper");
        assertEquals(btFills.size(), comparison.baseline().sampleSize());
        assertEquals(1, comparison.target().sampleSize());
        assertEquals(0.20, comparison.target().medianSlippage(), 1e-4);
        assertEquals(0.62, comparison.target().medianCommission(), 1e-4);
        String table = comparison.formatTable();
        assertTrue(table.contains("FILL LOG COMPARISON REPORT — sq-gold-m30 / MGC"));
        assertTrue(table.contains("Delta (paper - backtest)"));

        int exitCode = FuturesTradingEngine.runCli(
            new String[]{"--report", "sq-gold-m30", "MGC", "backtest", "paper"},
            repoRoot, fillsDir, reportsDir
        );
        assertEquals(0, exitCode);
    }

    @Test
    void a1AndA2_measureHistoricalBarsCompletenessAndCsvVsBarsAgreement() throws Exception {
        Path repoRoot = Paths.get(".").toAbsolutePath().normalize();
        Path barsDir = repoRoot.resolve("data/historical/bars");
        Path csvPath = repoRoot.resolve("data/historical/csv/MGC_M30_2026.csv");

        // A1: Verify all 24 annual MGC_M30_<year>.bars files (2003..2026) exist and measure completeness
        int totalBars2003To2026 = 0;
        int filesFound = 0;
        int intraWeekGapsOver72Hours = 0;
        Instant earliest = null;
        Instant latest = null;

        for (int year = 2003; year <= 2026; year++) {
            Path barFile = barsDir.resolve("MGC_M30_" + year + ".bars");
            assertTrue(Files.exists(barFile), "Missing annual MGC M30 file: " + barFile);
            filesFound++;
            List<Bar> yearBars = HistoricalDataLoader.loadYear("MGC", year, "M30", barsDir);
            assertFalse(yearBars.isEmpty(), "Empty bar file for year " + year);
            totalBars2003To2026 += yearBars.size();
            if (earliest == null || yearBars.get(0).timestamp().isBefore(earliest)) {
                earliest = yearBars.get(0).timestamp();
            }
            Bar lastBar = yearBars.get(yearBars.size() - 1);
            if (latest == null || lastBar.timestamp().isAfter(latest)) {
                latest = lastBar.timestamp();
            }
            for (int i = 1; i < yearBars.size(); i++) {
                Duration gap = Duration.between(yearBars.get(i - 1).timestamp(), yearBars.get(i).timestamp());
                // Weekend maintenance gap is ~48h; count gaps > 72h as multi-day holiday/missing stretches
                if (gap.toHours() > 72) {
                    intraWeekGapsOver72Hours++;
                }
            }
        }
        assertEquals(24, filesFound);
        assertTrue(totalBars2003To2026 > 250_000, "Expected > 250k M30 bars across 2003-2026, got " + totalBars2003To2026);

        // A2: Compare MGC_M30_2026.csv vs MGC_M30_2025..2026.bars over the exact CSV date window
        List<Bar> csvBars = FuturesTradingEngine.loadIbkrOrStandardCsv(csvPath, "MGC");
        Instant csvStart = csvBars.get(0).timestamp();
        Instant csvEnd = csvBars.get(csvBars.size() - 1).timestamp();

        List<Bar> bars25And26 = HistoricalDataLoader.loadYearRange("MGC", 2025, 2026, "M30", barsDir);
        List<Bar> barsWindow = new ArrayList<>();
        for (Bar b : bars25And26) {
            if (!b.timestamp().isBefore(csvStart) && !b.timestamp().isAfter(csvEnd)) {
                barsWindow.add(b);
            }
        }

        BacktestResult csvGoldRes = new BacktestEngine(new Strategy_Gold_Breakout_M30("MGC"), csvBars, 11_270.0)
            .withCommissionFixed(0.62)
            .withSlippageFixed(0.10)
            .withFillMode(FillMode.TOUCH)
            .withMarginTracking(true)
            .run();

        BacktestResult barsGoldRes = new BacktestEngine(new Strategy_Gold_Breakout_M30("MGC"), barsWindow, 11_270.0)
            .withCommissionFixed(0.62)
            .withSlippageFixed(0.10)
            .withFillMode(FillMode.TOUCH)
            .withMarginTracking(true)
            .run();

        System.out.printf(Locale.US,
            "A1 SUMMARY: files=%d totalBars=%d range=[%s .. %s] gapsOver72h=%d%n",
            filesFound, totalBars2003To2026, earliest, latest, intraWeekGapsOver72Hours);
        System.out.printf(Locale.US,
            "A2 SUMMARY (window %s .. %s): CSV[bars=%d trades=%d finalEquity=$%.2f] vs BARS[bars=%d trades=%d finalEquity=$%.2f]%n",
            csvStart, csvEnd,
            csvBars.size(), csvGoldRes.totalTrades(), csvGoldRes.finalEquity(),
            barsWindow.size(), barsGoldRes.totalTrades(), barsGoldRes.finalEquity());

        assertFalse(barsWindow.isEmpty());
    }

    private static final class CapturingBroker implements Broker {
        private final List<Consumer<BrokerEvent>> listeners = new ArrayList<>();
        private boolean connected;

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public void disconnect() {
            connected = false;
        }

        @Override
        public void reconnect() {
            connected = true;
        }

        @Override
        public OrderSubmitResult submitOrder(Order order) {
            return OrderSubmitResult.filled("1");
        }

        @Override
        public OrderSubmitResult cancelOrder(String brokerOrderId) {
            return OrderSubmitResult.filled(brokerOrderId);
        }

        @Override
        public int cancelAllOrders() {
            return 0;
        }

        @Override
        public int flattenAllPositions() {
            return 0;
        }

        @Override
        public List<Position> getPositions() {
            return List.of();
        }

        @Override
        public AccountState getAccountState() {
            return new AccountState(11270.0, 11270.0, "USD");
        }

        @Override
        public void addEventListener(Consumer<BrokerEvent> listener) {
            listeners.add(listener);
        }

        void fireEvent(BrokerEvent event) {
            for (Consumer<BrokerEvent> l : listeners) {
                l.accept(event);
            }
        }
    }
}
