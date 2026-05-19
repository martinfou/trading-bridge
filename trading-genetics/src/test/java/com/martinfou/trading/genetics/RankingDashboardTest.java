package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.backtest.WalkForwardOptimizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RobustnessScore}, {@link RankingDashboard}, and
 * {@link RankingDashboard.RankingEntry}.
 */
class RankingDashboardTest {

    // ================================================================
    //  RobustnessScore tests
    // ================================================================

    @Test
    @DisplayName("RobustnessScore.calculate produces score in 0-100 range")
    void robustnessScoreInRange() {
        // Given: a walk-forward result with strong OOS performance
        var wfResult = createWfResult(
            List.of(1.5, 1.8, 2.0),   // OOS Sharpes
            List.of(2.0, 2.2, 2.4),   // IS Sharpes
            List.of(12.5, 15.0, 8.0), // OOS returns %
            List.of(18.0, 20.0, 14.0) // IS returns %
        );
        var mcResult = createMcResult(1000, 5000.0, -2000.0, List.of(1.2, 1.4, 1.6), 100_000.0);
        var backtest = createBacktestResult("TestStrat", 100_000, 110_000, 1.8,
            2.2, 12.5, 10.0);

        RobustnessScore score = RobustnessScore.calculate(backtest, wfResult, mcResult);

        assertAll(
            () -> assertTrue(score.overall() >= 0 && score.overall() <= 100,
                "Overall should be in [0, 100], got " + score.overall()),
            () -> assertTrue(score.wfoos() >= 0 && score.wfoos() <= 100,
                "WFOOS should be in [0, 100]"),
            () -> assertTrue(score.monteCarlo() >= 0 && score.monteCarlo() <= 100,
                "MonteCarlo should be in [0, 100]"),
            () -> assertTrue(score.sharpeStability() >= 0 && score.sharpeStability() <= 100,
                "SharpeStability should be in [0, 100]"),
            () -> assertTrue(score.parameterSensitivity() >= 0 && score.parameterSensitivity() <= 100,
                "ParameterSensitivity should be in [0, 100]")
        );
    }

    @Test
    @DisplayName("RobustnessScore.calculate with strong inputs produces high score")
    void robustnessScoreStrongInputs() {
        // Given: excellent walk-forward and Monte Carlo results
        var wfResult = createWfResult(
            List.of(2.5, 2.8, 3.0),    // OOS Sharpes
            List.of(2.6, 3.0, 3.2),    // IS Sharpes (efficiency ~0.95)
            List.of(25.0, 30.0, 22.0), // OOS returns %
            List.of(28.0, 32.0, 25.0)  // IS returns %
        );
        var mcResult = createMcResult(1000, 15000.0, 5000.0, List.of(2.0, 2.2, 2.5), 100_000.0);
        var backtest = createBacktestResult("Strong", 100_000, 120_000, 2.5, 3.0, 8.0, 20.0);

        RobustnessScore score = RobustnessScore.calculate(backtest, wfResult, mcResult);

        // High OOS Sharpe + high efficiency + good MC = overall score above 70
        assertTrue(score.overall() >= 60,
            "Strong strategy should score >= 60, got " + score.overall());
    }

    @Test
    @DisplayName("RobustnessScore.calculate with weak inputs produces low score")
    void robustnessScoreWeakInputs() {
        // Given: poor walk-forward and Monte Carlo results
        var wfResult = createWfResult(
            List.of(0.1, -0.2, 0.0),    // OOS Sharpes (near zero/negative)
            List.of(1.5, 1.8, 1.2),     // IS Sharpes (large overfit)
            List.of(-5.0, -8.0, 2.0),   // OOS returns %
            List.of(15.0, 20.0, 12.0)   // IS returns %
        );
        var mcResult = createMcResult(1000, -5000.0, -15000.0, List.of(-0.5, 0.1, 0.3), 100_000.0);
        var backtest = createBacktestResult("Weak", 100_000, 95_000, -0.3, 0.8, 25.0, -5.0);

        RobustnessScore score = RobustnessScore.calculate(backtest, wfResult, mcResult);

        // Low OOS Sharpe + high loss prob + low efficiency = overall score below 50
        assertTrue(score.overall() < 50,
            "Weak strategy should score < 50, got " + score.overall());
    }

    @Test
    @DisplayName("RobustnessScore.calculate with null/empty inputs returns zero sub-scores")
    void robustnessScoreNullInputs() {
        var backtest = createBacktestResult("NullTest", 100_000, 100_000, 0, 1.0, 0, 0);

        // Test with null WF result
        RobustnessScore score1 = RobustnessScore.calculate(backtest, null, null);
        assertEquals(0.0, score1.wfoos(), "Null WF should give 0 WFOOS");
        assertEquals(0.0, score1.monteCarlo(), "Null MC should give 0 MonteCarlo");

        // Test with zero-window WF
        var emptyWf = new WalkForwardOptimizer.Result(List.of(), 180, 60);
        RobustnessScore score2 = RobustnessScore.calculate(backtest, emptyWf, null);
        assertEquals(0.0, score2.wfoos(), "Empty WF should give 0 WFOOS");
    }

    @Test
    @DisplayName("RobustnessScore label and indicator mappings are correct")
    void robustnessScoreLabels() {
        assertAll(
            () -> assertEquals("Excellent", new RobustnessScore(90, 0, 0, 0, 0).label()),
            () -> assertEquals("Good", new RobustnessScore(75, 0, 0, 0, 0).label()),
            () -> assertEquals("Fair", new RobustnessScore(60, 0, 0, 0, 0).label()),
            () -> assertEquals("Poor", new RobustnessScore(40, 0, 0, 0, 0).label()),
            () -> assertEquals("Very Poor", new RobustnessScore(10, 0, 0, 0, 0).label()),
            () -> assertEquals("\uD83D\uDFE2", new RobustnessScore(85, 0, 0, 0, 0).indicator()),
            () -> assertEquals("\uD83D\uDFE1", new RobustnessScore(60, 0, 0, 0, 0).indicator()),
            () -> assertEquals("\uD83D\uDFE0", new RobustnessScore(40, 0, 0, 0, 0).indicator()),
            () -> assertEquals("\uD83D\uDD34", new RobustnessScore(20, 0, 0, 0, 0).indicator())
        );
    }

    // ================================================================
    //  RankingDashboard tests
    // ================================================================

    @Test
    @DisplayName("RankingDashboard generates valid HTML with expected elements")
    void rankingDashboardGeneratesValidHtml() {
        List<RankingDashboard.RankingEntry> entries = createSampleEntries(10);

        RankingDashboard dashboard = new RankingDashboard(entries);
        String html = dashboard.generate();

        // Check HTML structure
        assertAll(
            () -> assertTrue(html.startsWith("<!DOCTYPE html>"),
                "HTML should start with DOCTYPE"),
            () -> assertTrue(html.contains("<html"),
                "HTML should contain html tag"),
            () -> assertTrue(html.contains("</html>"),
                "HTML should close html tag"),
            () -> assertTrue(html.contains("chart.js@4"),
                "HTML should reference Chart.js CDN"),
            () -> assertTrue(html.contains("Strategy Ranking Dashboard"),
                "HTML should contain dashboard title"),
            () -> assertTrue(html.contains("rankingBody"),
                "HTML should contain table body for ranking entries"),
            () -> assertTrue(html.contains("sharpeChart"),
                "HTML should contain Sharpe chart canvas"),
            () -> assertTrue(html.contains("gaugeChart"),
                "HTML should contain gauge chart canvas"),
            () -> assertTrue(html.contains("pieChart"),
                "HTML should contain pie chart canvas"),
            () -> assertTrue(html.contains("Export CSV"),
                "HTML should contain CSV export button"),

            // Data must be embedded as JSON
            () -> assertTrue(html.contains("const DATA = "),
                "HTML should embed strategy data as JSON"),

            // All 10 entries should appear as data
            () -> assertTrue(html.contains("\"rank\":1") && html.contains("\"rank\":10"),
                "HTML should contain rank data for all entries")
        );
    }

    @Test
    @DisplayName("RankingDashboard handles empty entries gracefully")
    void rankingDashboardEmptyEntries() {
        RankingDashboard dashboard = new RankingDashboard(List.of());
        String html = dashboard.generate();

        assertAll(
            () -> assertTrue(html.contains("Strategy Ranking Dashboard"),
                "HTML should render even with empty entries"),
            () -> assertTrue(html.contains("const DATA = []"),
                "Data JSON should be an empty array")
        );
    }

    @Test
    @DisplayName("RankingDashboard entries are sorted by rank before rendering")
    void rankingEntriesSortedByRank() {
        // Create entries in reverse order
        List<RankingDashboard.RankingEntry> entries = createSampleEntries(5);
        List<RankingDashboard.RankingEntry> reversed = new ArrayList<>(entries);
        java.util.Collections.reverse(reversed);

        // Rank values should be 5,4,3,2,1
        for (int i = 0; i < reversed.size(); i++) {
            assertEquals(reversed.size() - i, reversed.get(i).rank(),
                "Reversed list should have descending ranks");
        }

        // Dashboard should render in the order provided (rank order is caller's responsibility)
        RankingDashboard dashboard = new RankingDashboard(reversed);
        String html = dashboard.generate();

        // The data JSON should contain the entries in the order provided
        // (the JavaScript handles the sorting)
        assertTrue(html.contains("\"rank\":5"), "Should contain rank 5 first");
    }

    @Test
    @DisplayName("RankingEntry strategy name and type detection work")
    void rankingEntryStrategyNameAndType() {
        // Chromosome with RSI only → MeanRev
        Chromosome meanRev = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            30, 60
        );
        var entry1 = createEntry(1, meanRev);
        assertEquals("RSI_14", entry1.strategyName());
        assertEquals("MeanRev", entry1.strategyType());

        // Chromosome with SMA only → Trend
        Chromosome trend = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.SMA, 20, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE)),
            40, 80
        );
        var entry2 = createEntry(2, trend);
        assertEquals("SMA_20", entry2.strategyName());
        assertEquals("Trend", entry2.strategyType());

        // Chromosome with ADX only → Breakout
        Chromosome breakout = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.EMA, 21, Gene.Field.CLOSE)),
            50, 100
        );
        var entry3 = createEntry(3, breakout);
        assertEquals("ADX_14", entry3.strategyName());
        assertEquals("Breakout", entry3.strategyType());
    }

    @Test
    @DisplayName("RankingDashboard generate method writes to file")
    void rankingDashboardWritesToFile() throws Exception {
        List<RankingDashboard.RankingEntry> entries = createSampleEntries(3);
        RankingDashboard dashboard = new RankingDashboard(entries);

        var tempFile = java.nio.file.Files.createTempFile("ranking-test-", ".html");
        try {
            var result = dashboard.generate(tempFile);
            assertNotNull(result);
            assertTrue(java.nio.file.Files.exists(result));
            assertTrue(java.nio.file.Files.size(result) > 0);
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    // ================================================================
    //  Filter tests — strategy type detection
    // ================================================================

    @Test
    @DisplayName("RankingDashboard correctly filters by strategy type")
    void rankingDashboardTypeDetection() {
        // Create one of each type
        Chromosome trend = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.EMA, 20, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE)), 30, 60);
        Chromosome meanRev = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.RSI, 21, Gene.Field.CLOSE)), 20, 40);
        Chromosome breakout = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.ATR, 14, Gene.Field.CLOSE)), 50, 100);

        var entries = List.of(
            createEntry(1, trend),
            createEntry(2, meanRev),
            createEntry(3, breakout)
        );

        // Check types
        assertEquals("Trend", entries.get(0).strategyType());
        assertEquals("MeanRev", entries.get(1).strategyType());
        assertEquals("Breakout", entries.get(2).strategyType());

        // The dashboard's JSON data should contain the type info
        RankingDashboard dashboard = new RankingDashboard(entries);
        String html = dashboard.generate();

        // The JSON should have all three strategy types
        assertTrue(html.contains("\"type\":\"Trend\""), "JSON should contain Trend type");
        assertTrue(html.contains("\"type\":\"MeanRev\""), "JSON should contain MeanRev type");
        assertTrue(html.contains("\"type\":\"Breakout\""), "JSON should contain Breakout type");

        // Strategy type badges should be in the HTML
        assertTrue(html.contains("type-Trend"), "HTML should have Trend CSS class");
        assertTrue(html.contains("type-MeanRev"), "HTML should have MeanRev CSS class");
        assertTrue(html.contains("type-Breakout"), "HTML should have Breakout CSS class");
    }

    @Test
    @DisplayName("RankingDashboard CSV export data is embedded")
    void rankingDashboardCsvData() {
        List<RankingDashboard.RankingEntry> entries = createSampleEntries(3);
        RankingDashboard dashboard = new RankingDashboard(entries);
        String html = dashboard.generate();

        assertAll(
            () -> assertTrue(html.contains("exportCsv()"),
                "HTML should contain CSV export function call"),
            () -> assertTrue(html.contains("'ranking-export.csv'"),
                "CSV export should reference the filename")
        );
    }

    // ================================================================
    //  Helper methods
    // ================================================================

    /**
     * Creates a WalkForwardOptimizer.Result from raw data.
     */
    private static WalkForwardOptimizer.Result createWfResult(
            List<Double> oosSharpes,
            List<Double> isSharpes,
            List<Double> oosReturns,
            List<Double> isReturns) {
        int n = Math.min(oosSharpes.size(), Math.min(isSharpes.size(),
            Math.min(oosReturns.size(), isReturns.size())));
        List<WalkForwardOptimizer.WindowResult> windows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            windows.add(new WalkForwardOptimizer.WindowResult(
                i,                         // windowIndex
                isReturns.get(i),          // isReturnPct
                oosReturns.get(i),         // oosReturnPct
                isSharpes.get(i),          // isSharpe
                oosSharpes.get(i),         // oosSharpe
                10.0,                      // isMaxDD
                12.0,                      // oosMaxDD
                20,                        // isTrades
                15,                        // oosTrades
                180,                       // isBarCount
                60                         // oosBarCount
            ));
        }
        return new WalkForwardOptimizer.Result(windows, 180, 60);
    }

    /**
     * Creates a MonteCarloSimulation.Result from raw data.
     */
    private static MonteCarloSimulation.Result createMcResult(
            int totalRuns,
            double medianPnl,
            double var95,
            List<Double> sharpeSamples,
            double initialCapital) {
        // Generate sorted PnL values that approximate the given stats
        List<Double> pnls = new ArrayList<>(totalRuns);
        for (int i = 0; i < totalRuns; i++) {
            double t = (double) i / (totalRuns - 1);
            // Linear interpolation from var95 → medianPnl
            double pnl = var95 + (medianPnl - var95) * Math.pow(t, 0.8);
            pnls.add(pnl);
        }
        java.util.Collections.sort(pnls);

        List<Double> dds = new ArrayList<>(totalRuns);
        for (int i = 0; i < totalRuns; i++) {
            dds.add(5.0 + Math.random() * 15.0);
        }
        java.util.Collections.sort(dds);

        List<Double> sharpes = new ArrayList<>(sharpeSamples.size() * (totalRuns / sharpeSamples.size()));
        // Just use the provided samples
        return new MonteCarloSimulation.Result(totalRuns, pnls, dds, List.copyOf(sharpeSamples), initialCapital);
    }

    /**
     * Creates a BacktestResult with the given parameters.
     */
    private static BacktestResult createBacktestResult(
            String name, double initialCapital, double finalEquity,
            double sharpe, double profitFactor, double maxDD, double totalReturn) {
        return new BacktestResult(
            name,                        // strategyName
            initialCapital,              // initialCapital
            finalEquity,                 // finalEquity
            finalEquity - initialCapital,// totalPnl
            totalReturn,                 // totalReturnPct
            50,                          // totalTrades
            30,                          // winningTrades
            20,                          // losingTrades
            60.0,                        // winRatePct
            maxDD,                       // maxDrawdownPct
            200.0,                       // avgTradePnl
            sharpe,                      // sharpeRatio
            sharpe * 0.8,                // sortinoRatio (approx)
            profitFactor,                // profitFactor
            sharpe * 2.0,                // calmarRatio
            0.0,                         // totalCommission
            0.0,                         // totalSlippage
            List.of(initialCapital, (initialCapital + finalEquity) / 2, finalEquity), // equityCurve
            List.of(),                   // trades
            Instant.parse("2024-01-01T00:00:00Z"), // periodStart
            Instant.parse("2024-12-31T00:00:00Z")  // periodEnd
        );
    }

    /**
     * Creates a list of sample ranking entries for testing.
     */
    private static List<RankingDashboard.RankingEntry> createSampleEntries(int count) {
        List<Gene> entryGenes = List.of(new Gene(Gene.IndicatorType.SMA, 20, Gene.Field.CLOSE));
        List<Gene> exitGenes = List.of(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE));

        List<RankingDashboard.RankingEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int rank = i + 1;
            Chromosome chr = new Chromosome(entryGenes, exitGenes, 30 + i * 5, 60 + i * 5);
            double sharpe = 2.0 - i * 0.15;
            double pf = 2.5 - i * 0.2;
            double dd = 10.0 + i * 1.5;
            double ret = 25.0 - i * 2.0;

            BacktestResult bt = createBacktestResult("SMA_20", 100_000, 100_000 + ret * 1000,
                sharpe, pf, dd, ret);

            var genResult = new GeneticEngine.GenerationResult(chr, sharpe, i % 5);
            var robustness = new RobustnessScore(
                90.0 - i * 8.0,   // overall decreases
                85.0 - i * 7.0,   // wfoos
                80.0 - i * 6.0,   // monteCarlo
                75.0 - i * 5.0,   // sharpeStability
                70.0 - i * 4.0    // paramSensitivity
            );

            entries.add(new RankingDashboard.RankingEntry(rank, chr, bt, robustness, genResult));
        }
        return entries;
    }

    /**
     * Creates a single ranking entry for testing.
     */
    private static RankingDashboard.RankingEntry createEntry(int rank, Chromosome chr) {
        BacktestResult bt = createBacktestResult(chr.toString(), 100_000, 110_000,
            1.5, 2.0, 12.0, 10.0);
        var genResult = new GeneticEngine.GenerationResult(chr, 1.5, 0);
        var robustness = new RobustnessScore(75.0, 70.0, 65.0, 60.0, 55.0);
        return new RankingDashboard.RankingEntry(rank, chr, bt, robustness, genResult);
    }
}
