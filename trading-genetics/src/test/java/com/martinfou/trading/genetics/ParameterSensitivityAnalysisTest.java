package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ParameterSensitivityAnalysis}.
 */
class ParameterSensitivityAnalysisTest {

    private static final double INITIAL_CAPITAL = 100_000.0;

    @Test
    @DisplayName("Returns a non-empty report for a random chromosome")
    void testAnalyzeReturnsReport() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        assertNotNull(report);
        assertNotNull(report.perParameter());
        assertFalse(report.perParameter().isEmpty(),
            "Should have at least one parameter result");
    }

    @Test
    @DisplayName("Each parameter result has a valid stability score between 0 and 100")
    void testAllStabilityScoresInRange() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        for (var result : report.perParameter()) {
            assertTrue(result.stabilityScore() >= 0.0 && result.stabilityScore() <= 100.0,
                () -> "Stability score for '" + result.paramName()
                    + "' should be in [0, 100] but was " + result.stabilityScore());
        }
    }

    @Test
    @DisplayName("Global score is the average of per-parameter scores")
    void testGlobalScoreIsAverage() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        double expectedAvg = report.perParameter().stream()
            .mapToDouble(ParameterSensitivityAnalysis.SensitivityResult::stabilityScore)
            .average()
            .orElse(0.0);

        assertEquals(expectedAvg, report.globalScore(), 0.001,
            "Global score should be the average of per-parameter scores");
    }

    @Test
    @DisplayName("All parameter names are non-null and descriptive")
    void testParameterNames() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        for (var result : report.perParameter()) {
            assertNotNull(result.paramName());
            assertFalse(result.paramName().isBlank());

            boolean isEntry = result.paramName().contains("entry");
            boolean isExit = result.paramName().contains("exit");
            boolean isSl = result.paramName().contains("stopLoss");
            boolean isTp = result.paramName().contains("takeProfit");
            assertTrue(isEntry || isExit || isSl || isTp,
                () -> "Unexpected parameter name: " + result.paramName());
        }
    }

    @Test
    @DisplayName("Reports the correct number of parameters")
    void testParameterCount() {
        // Create a chromosome with known counts
        List<Gene> entries = List.of(
            new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.RSI, 7, Gene.Field.CLOSE)
        );
        List<Gene> exits = List.of(
            new Gene(Gene.IndicatorType.EMA, 21, Gene.Field.CLOSE)
        );
        Chromosome chromosome = new Chromosome(entries, exits, 50, 100);
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        // 2 entry periods + 1 exit period + stopLoss + takeProfit = 5
        assertEquals(5, report.perParameter().size(),
            "Should have 5 parameters (2 entry + 1 exit + SL + TP)");
    }

    @Test
    @DisplayName("Perturbations produce different test values (parameter is actually varied)")
    void testPerturbationsProduceDifferentValues() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        for (var result : report.perParameter()) {
            // The test values should differ from the base value
            boolean allSameAsBase = result.testValues().stream()
                .allMatch(v -> Math.abs(v - result.baseValue()) < 0.001);
            // At least one value should differ unless all perturbations clamp to same value
            // (possible for very small periods like 2)
            if (result.baseValue() > 3) {
                assertFalse(allSameAsBase,
                    () -> "Parameter '" + result.paramName() + "' should have at least one "
                        + "perturbed value different from base " + result.baseValue());
            }
        }
    }

    @Test
    @DisplayName("Each parameter has same number of test values and Sharpe values")
    void testTestAndSharpeValuesMatch() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        for (var result : report.perParameter()) {
            assertEquals(result.testValues().size(), result.sharpeValues().size(),
                () -> "Parameter '" + result.paramName()
                    + "' should have matching test/sharpe count");
        }
    }

    @Test
    @DisplayName("Throws on null chromosome")
    void testNullChromosome() {
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        BacktestResult baseResult = backtest(Chromosome.random(), bars);
        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();

        assertThrows(NullPointerException.class,
            () -> analysis.analyze(null, baseResult, bars, INITIAL_CAPITAL));
    }

    @Test
    @DisplayName("Throws on null bars")
    void testNullBars() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);
        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();

        assertThrows(NullPointerException.class,
            () -> analysis.analyze(chromosome, baseResult, null, INITIAL_CAPITAL));
    }

    @Test
    @DisplayName("Throws on empty bars")
    void testEmptyBars() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);
        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();

        assertThrows(IllegalArgumentException.class,
            () -> analysis.analyze(chromosome, baseResult, List.of(), INITIAL_CAPITAL));
    }

    @Test
    @DisplayName("Throws on null baseResult")
    void testNullBaseResult() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();

        assertThrows(NullPointerException.class,
            () -> analysis.analyze(chromosome, null, bars, INITIAL_CAPITAL));
    }

    @Test
    @DisplayName("Analyze produces deterministic results for same inputs")
    void testDeterministic() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateSineBars(200, 100.0, 5.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();

        var report1 = analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);
        var report2 = analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        assertEquals(report1.globalScore(), report2.globalScore(), 0.0001,
            "Global score should be deterministic");
        assertEquals(report1.globalStdDev(), report2.globalStdDev(), 0.0001,
            "Global stddev should be deterministic");

        for (int i = 0; i < report1.perParameter().size(); i++) {
            var r1 = report1.perParameter().get(i);
            var r2 = report2.perParameter().get(i);
            assertEquals(r1.stabilityScore(), r2.stabilityScore(), 0.0001,
                () -> "Stability score for '" + r1.paramName() + "' should be deterministic");
        }
    }

    @Test
    @DisplayName("Report toString does not throw")
    void testToStringDoesNotThrow() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateTrendingBars(200, 100.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        assertDoesNotThrow(() -> report.toString());

        for (var result : report.perParameter()) {
            assertDoesNotThrow(result::toString);
        }
    }

    @Test
    @DisplayName("Works with sine-wave bars for predictable test data")
    void testSineWaveBars() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = TestBarData.generateSineBars(200, 100.0, 5.0);
        BacktestResult baseResult = backtest(chromosome, bars);

        ParameterSensitivityAnalysis analysis = new ParameterSensitivityAnalysis();
        ParameterSensitivityAnalysis.SensitivityReport report =
            analysis.analyze(chromosome, baseResult, bars, INITIAL_CAPITAL);

        assertNotNull(report);
        assertFalse(report.perParameter().isEmpty());
        assertTrue(report.globalScore() >= 0.0 && report.globalScore() <= 100.0);
    }

    // ---------------------------------------------------------------
    //  Test helpers
    // ---------------------------------------------------------------

    private BacktestResult backtest(Chromosome chromosome, List<Bar> bars) {
        StrategyTemplate strategy = new StrategyTemplate(chromosome);
        BacktestEngine engine = new BacktestEngine(strategy, bars, INITIAL_CAPITAL);
        engine.withCommissionFixed(0.0)
            .withCommissionPct(0.0007);
        return engine.run();
    }
}
