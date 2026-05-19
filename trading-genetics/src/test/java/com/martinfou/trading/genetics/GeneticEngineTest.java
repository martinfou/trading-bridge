package com.martinfou.trading.genetics;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GeneticEngine}.
 */
class GeneticEngineTest {

    @Test
    void testEngineRunsWithoutError() {
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        GeneticEngine engine = new GeneticEngine(bars,
            10,   // small population for test speed
            5,    // few generations
            0.1,
            0.8,
            0.1,
            100_000.0,
            new SelectionStrategy.TournamentSelection(3)
        );

        GeneticEngine.GenerationResult result = engine.run();
        assertNotNull(result);
        assertNotNull(result.best());
        assertTrue(result.generation() >= 0, "Generation index should be >= 0");
    }

    @Test
    void testEngineProducesValidChromosome() {
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        GeneticEngine engine = new GeneticEngine(bars,
            10, 5, 0.1, 0.8, 0.1, 100_000.0,
            new SelectionStrategy.TournamentSelection(3)
        );

        GeneticEngine.GenerationResult result = engine.run();
        Chromosome best = result.best();

        assertNotNull(best);
        assertFalse(best.entryGenes().isEmpty(), "Best chromosome must have entry genes");
        assertFalse(best.exitGenes().isEmpty(), "Best chromosome must have exit genes");
        assertTrue(result.fitness() > 0, "Fitness should be positive for a valid result");
    }

    @Test
    void testEngineSummaryDoesNotThrow() {
        List<Bar> bars = TestBarData.generateTrendingBars(100, 100.0);
        GeneticEngine engine = new GeneticEngine(bars,
            10, 5, 0.1, 0.8, 0.1, 100_000.0,
            new SelectionStrategy.TournamentSelection(3)
        );

        GeneticEngine.GenerationResult result = engine.run();
        String summary = result.summary();
        assertNotNull(summary);
        assertTrue(summary.contains("Fitness"), "Summary should contain 'Fitness'");
    }

    @Test
    void testEngineRejectsEmptyBars() {
        assertThrows(IllegalArgumentException.class, () ->
            new GeneticEngine(List.of())
        );
    }

    @Test
    void testEngineRejectsSmallPopulation() {
        List<Bar> bars = TestBarData.generateTrendingBars(20, 100.0);
        assertThrows(IllegalArgumentException.class, () ->
            new GeneticEngine(bars, 2, 5, 0.1, 0.8, 0.1, 100_000.0,
                new SelectionStrategy.TournamentSelection(3))
        );
    }

    @Test
    void testEngineWorksWithSineBars() {
        List<Bar> bars = TestBarData.generateSineBars(200, 100.0, 5.0);
        GeneticEngine engine = new GeneticEngine(bars,
            10, 3, 0.1, 0.8, 0.1, 100_000.0,
            new SelectionStrategy.TournamentSelection(3)
        );
        GeneticEngine.GenerationResult result = engine.run();
        assertNotNull(result.best());
    }
}
