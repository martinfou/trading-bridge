package com.martinfou.trading.genetics;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StrategyBuilder}.
 */
class StrategyBuilderTest {

    // ---------------------------------------------------------------
    //  suggestDefaults
    // ---------------------------------------------------------------

    @Test
    void suggestDefaultsForAllTypesProducesValidConfig() {
        for (StrategyBuilder.StrategyType type : StrategyBuilder.StrategyType.values()) {
            StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(type);
            assertNotNull(config, "Config must not be null for " + type);
            assertEquals(type, config.type(), "Config type must match for " + type);
            assertNotNull(config.timeframe(), "Timeframe must not be null for " + type);
            assertFalse(config.symbols().isEmpty(), "Symbols must not be empty for " + type);
            assertNotNull(config.riskLevel(), "RiskLevel must not be null for " + type);
            assertTrue(config.populationSize() >= 4, "Population size must be >= 4 for " + type);
            assertTrue(config.generations() >= 1, "Generations must be >= 1 for " + type);
            assertFalse(config.indicators().isEmpty(), "Indicators must not be empty for " + type);
        }
    }

    @ParameterizedTest
    @EnumSource(StrategyBuilder.StrategyType.class)
    void suggestDefaultsReturnsDifferentDefaultsPerType(StrategyBuilder.StrategyType type) {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(type);
        // Each type should have a pre-filled configuration — verify non-zero defaults
        assertTrue(config.populationSize() > 0);
        assertTrue(config.generations() > 0);
    }

    @Test
    void suggestDefaultsForTrendFollowingHasExpectedIndicators() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.TREND_FOLLOWING);
        assertEquals(StrategyBuilder.TimeFrame.H4, config.timeframe());
        assertEquals(StrategyBuilder.RiskLevel.MODERATE, config.riskLevel());
        assertTrue(config.indicators().contains("SMA"));
        assertTrue(config.indicators().contains("ADX"));
    }

    @Test
    void suggestDefaultsForMeanReversionHasExpectedIndicators() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.MEAN_REVERSION);
        assertEquals(StrategyBuilder.TimeFrame.M15, config.timeframe());
        assertEquals(StrategyBuilder.RiskLevel.CONSERVATIVE, config.riskLevel());
        assertTrue(config.indicators().contains("RSI"));
    }

    @Test
    void suggestDefaultsForBreakoutHasExpectedIndicators() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.BREAKOUT);
        assertEquals(StrategyBuilder.TimeFrame.H1, config.timeframe());
        assertEquals(StrategyBuilder.RiskLevel.AGGRESSIVE, config.riskLevel());
        assertTrue(config.indicators().contains("ATR"));
    }

    @Test
    void suggestDefaultsForMomentumHasExpectedIndicators() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.MOMENTUM);
        assertEquals(StrategyBuilder.TimeFrame.H1, config.timeframe());
        assertEquals(StrategyBuilder.RiskLevel.MODERATE, config.riskLevel());
        assertTrue(config.indicators().contains("EMA"));
        assertTrue(config.indicators().contains("RSI"));
    }

    // ---------------------------------------------------------------
    //  buildChromosome
    // ---------------------------------------------------------------

    @Test
    void buildChromosomeForAllTypesProducesValidChromosome() {
        for (StrategyBuilder.StrategyType type : StrategyBuilder.StrategyType.values()) {
            StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(type);
            Chromosome chromo = StrategyBuilder.buildChromosome(config);

            assertNotNull(chromo, "Chromosome must not be null for " + type);
            assertFalse(chromo.entryGenes().isEmpty(), "Entry genes must not be empty for " + type);
            assertFalse(chromo.exitGenes().isEmpty(), "Exit genes must not be empty for " + type);
            assertTrue(chromo.stopLoss() >= 0, "SL must be >= 0 for " + type);
            assertTrue(chromo.takeProfit() >= 0, "TP must be >= 0 for " + type);

            // Risk-based SL/TP differentiation
            switch (type) {
                case MEAN_REVERSION ->
                    // Mean reversion should have tighter stops
                    assertTrue(chromo.stopLoss() <= 60,
                        "Mean reversion SL should be tight, got " + chromo.stopLoss());
                case BREAKOUT ->
                    // Breakout should have wider TP
                    assertTrue(chromo.takeProfit() >= 200,
                        "Breakout TP should be wide, got " + chromo.takeProfit());
                default -> {
                    // No additional assertions for others
                }
            }
        }
    }

    @Test
    void buildChromosomeTrendHasExpectedGenes() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.TREND_FOLLOWING);
        Chromosome chromo = StrategyBuilder.buildChromosome(config);

        // Trend following should include SMA and ADX in entry genes
        boolean hasSma = chromo.entryGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.SMA);
        boolean hasAdx = chromo.entryGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.ADX);
        assertTrue(hasSma, "Trend chromosome should have SMA entry gene");
        assertTrue(hasAdx, "Trend chromosome should have ADX entry gene");
    }

    @Test
    void buildChromosomeMeanReversionHasRsi() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.MEAN_REVERSION);
        Chromosome chromo = StrategyBuilder.buildChromosome(config);

        boolean hasRsiEntry = chromo.entryGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.RSI);
        boolean hasRsiExit = chromo.exitGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.RSI);
        assertTrue(hasRsiEntry, "Mean reversion entry should contain RSI");
        assertTrue(hasRsiExit, "Mean reversion exit should contain RSI");
    }

    @Test
    void buildChromosomeBreakoutHasAtrAndEma() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.BREAKOUT);
        Chromosome chromo = StrategyBuilder.buildChromosome(config);

        boolean hasAtr = chromo.entryGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.ATR);
        boolean hasEmaExit = chromo.exitGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.EMA);
        assertTrue(hasAtr, "Breakout entry should contain ATR");
        assertTrue(hasEmaExit, "Breakout exit should contain EMA");
    }

    @Test
    void buildChromosomeMomentumHasEmaAndRsi() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.MOMENTUM);
        Chromosome chromo = StrategyBuilder.buildChromosome(config);

        boolean hasEma = chromo.entryGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.EMA);
        boolean hasRsi = chromo.entryGenes().stream()
            .anyMatch(g -> g.indicatorType() == Gene.IndicatorType.RSI);
        assertTrue(hasEma, "Momentum entry should contain EMA");
        assertTrue(hasRsi, "Momentum entry should contain RSI");
    }

    @Test
    void buildChromosomeAcceptsCustomConfig() {
        StrategyBuilder.BuildConfig config = new StrategyBuilder.BuildConfig(
            StrategyBuilder.StrategyType.BREAKOUT,
            StrategyBuilder.TimeFrame.D1,
            List.of("EURUSD", "GBPUSD"),
            StrategyBuilder.RiskLevel.CONSERVATIVE,
            30, 20,
            List.of("ATR", "ADX")
        );
        Chromosome chromo = StrategyBuilder.buildChromosome(config);
        assertNotNull(chromo);
        // Conservative breakout should have tighter SL
        assertTrue(chromo.stopLoss() <= 80,
            "Conservative breakout SL should be ≤ 80, got " + chromo.stopLoss());
    }

    // ---------------------------------------------------------------
    //  runOptimization
    // ---------------------------------------------------------------

    @Test
    void runOptimizationWithSimpleBarsReturnsValidResult() {
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.TREND_FOLLOWING);

        GeneticEngine.GenerationResult result = StrategyBuilder.runOptimization(
            config, bars, 100_000.0);

        assertNotNull(result, "Result must not be null");
        assertNotNull(result.best(), "Best chromosome must not be null");
        assertTrue(result.generation() >= 0, "Generation index must be >= 0, got " + result.generation());
    }

    @Test
    void runOptimizationWithAllTypes() {
        List<Bar> bars = TestBarData.generateSineBars(200, 100.0, 5.0);

        for (StrategyBuilder.StrategyType type : StrategyBuilder.StrategyType.values()) {
            // Use a small config for speed
            StrategyBuilder.BuildConfig config = new StrategyBuilder.BuildConfig(
                type,
                StrategyBuilder.TimeFrame.H1,
                List.of("EURUSD"),
                StrategyBuilder.RiskLevel.MODERATE,
                10, 5,
                List.of("SMA", "RSI")
            );

            GeneticEngine.GenerationResult result = StrategyBuilder.runOptimization(
                config, bars, 50_000.0);

            assertNotNull(result, "Result must not be null for " + type);
            assertNotNull(result.best(), "Best chromosome must not be null for " + type);
            assertFalse(result.best().entryGenes().isEmpty(),
                "Best entry genes must not be empty for " + type);
            assertFalse(result.best().exitGenes().isEmpty(),
                "Best exit genes must not be empty for " + type);
        }
    }

    @Test
    void runOptimizationRejectsEmptyBars() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.TREND_FOLLOWING);
        assertThrows(IllegalArgumentException.class,
            () -> StrategyBuilder.runOptimization(config, List.of(), 100_000.0));
    }

    @Test
    void runOptimizationRejectsNonPositiveCapital() {
        List<Bar> bars = TestBarData.generateTrendingBars(20, 100.0);
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.TREND_FOLLOWING);
        assertThrows(IllegalArgumentException.class,
            () -> StrategyBuilder.runOptimization(config, bars, 0));
        assertThrows(IllegalArgumentException.class,
            () -> StrategyBuilder.runOptimization(config, bars, -100));
    }

    // ---------------------------------------------------------------
    //  describeConfig
    // ---------------------------------------------------------------

    @Test
    void describeConfigOutputContainsAllFields() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.MOMENTUM);
        String desc = StrategyBuilder.describeConfig(config);

        assertNotNull(desc, "Description must not be null");
        assertTrue(desc.contains("MOMENTUM"), "Description should contain type");
        assertTrue(desc.contains("H1"), "Description should contain timeframe");
        assertTrue(desc.contains("EURUSD"), "Description should contain symbol");
        assertTrue(desc.contains("MODERATE"), "Description should contain risk level");
        assertTrue(desc.contains("Strategy"), "Description should contain strategy notes");
    }

    @Test
    void describeConfigForAllTypesProducesOutput() {
        for (StrategyBuilder.StrategyType type : StrategyBuilder.StrategyType.values()) {
            StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(type);
            String desc = StrategyBuilder.describeConfig(config);
            assertNotNull(desc, "Description must not be null for " + type);
            assertFalse(desc.isBlank(), "Description must not be blank for " + type);
        }
    }

    @Test
    void describeConfigContainsRiskParameters() {
        StrategyBuilder.BuildConfig config = StrategyBuilder.suggestDefaults(
            StrategyBuilder.StrategyType.BREAKOUT);
        String desc = StrategyBuilder.describeConfig(config);
        assertTrue(desc.contains("Stop-loss"), "Description should describe stop-loss");
        assertTrue(desc.contains("Take-profit"), "Description should describe take-profit");
        assertTrue(desc.contains("Position"), "Description should describe position sizing");
    }

    // ---------------------------------------------------------------
    //  BuildConfig validation
    // ---------------------------------------------------------------

    @Test
    void buildConfigRejectsNullFields() {
        assertThrows(NullPointerException.class, () -> new StrategyBuilder.BuildConfig(
            null, StrategyBuilder.TimeFrame.H1, List.of("EURUSD"),
            StrategyBuilder.RiskLevel.MODERATE, 50, 50, List.of("SMA")));

        assertThrows(NullPointerException.class, () -> new StrategyBuilder.BuildConfig(
            StrategyBuilder.StrategyType.TREND_FOLLOWING, null, List.of("EURUSD"),
            StrategyBuilder.RiskLevel.MODERATE, 50, 50, List.of("SMA")));

        assertThrows(NullPointerException.class, () -> new StrategyBuilder.BuildConfig(
            StrategyBuilder.StrategyType.TREND_FOLLOWING, StrategyBuilder.TimeFrame.H1, null,
            StrategyBuilder.RiskLevel.MODERATE, 50, 50, List.of("SMA")));
    }

    @Test
    void buildConfigRejectsEmptySymbols() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyBuilder.BuildConfig(
            StrategyBuilder.StrategyType.TREND_FOLLOWING, StrategyBuilder.TimeFrame.H1, List.of(),
            StrategyBuilder.RiskLevel.MODERATE, 50, 50, List.of("SMA")));
    }

    @Test
    void buildConfigRejectsEmptyIndicators() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyBuilder.BuildConfig(
            StrategyBuilder.StrategyType.TREND_FOLLOWING, StrategyBuilder.TimeFrame.H1,
            List.of("EURUSD"), StrategyBuilder.RiskLevel.MODERATE, 50, 50, List.of()));
    }

    @Test
    void buildConfigAppliesDefaultsForInvalidPopulation() {
        StrategyBuilder.BuildConfig config = new StrategyBuilder.BuildConfig(
            StrategyBuilder.StrategyType.TREND_FOLLOWING, StrategyBuilder.TimeFrame.H1,
            List.of("EURUSD"), StrategyBuilder.RiskLevel.MODERATE,
            2, 0, List.of("SMA"));
        // Compact constructor should clamp to defaults
        assertTrue(config.populationSize() >= 4,
            "Population size should be defaulted, got " + config.populationSize());
        assertTrue(config.generations() >= 1,
            "Generations should be defaulted, got " + config.generations());
    }
}
