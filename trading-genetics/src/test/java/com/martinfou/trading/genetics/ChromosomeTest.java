package com.martinfou.trading.genetics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Chromosome}.
 */
class ChromosomeTest {

    private final Gene sma14Close = new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE);
    private final Gene ema20Close = new Gene(Gene.IndicatorType.EMA, 20, Gene.Field.CLOSE);
    private final Gene rsi14 = new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE);

    @Test
    void testConstructorValidChromosome() {
        Chromosome c = new Chromosome(
            List.of(sma14Close, ema20Close),
            List.of(rsi14),
            30, 60
        );
        assertEquals(2, c.entryGenes().size());
        assertEquals(1, c.exitGenes().size());
        assertEquals(30, c.stopLoss());
        assertEquals(60, c.takeProfit());
    }

    @Test
    void testConstructorRejectsEmptyEntries() {
        assertThrows(IllegalArgumentException.class, () ->
            new Chromosome(List.of(), List.of(rsi14), 0, 0));
    }

    @Test
    void testConstructorRejectsEmptyExits() {
        assertThrows(IllegalArgumentException.class, () ->
            new Chromosome(List.of(sma14Close), List.of(), 0, 0));
    }

    @Test
    void testConstructorClampsRiskParameters() {
        Chromosome c = new Chromosome(
            List.of(sma14Close),
            List.of(rsi14),
            -100, 9999
        );
        assertEquals(0, c.stopLoss(), "SL should be clamped to minimum 0");
        assertEquals(500, c.takeProfit(), "TP should be clamped to maximum 500");
    }

    @RepeatedTest(10)
    void testChromosomeRandomProducesValidChromosome() {
        Chromosome c = Chromosome.random();
        assertNotNull(c);
        assertFalse(c.entryGenes().isEmpty(), "Random chromosome must have entry genes");
        assertFalse(c.exitGenes().isEmpty(), "Random chromosome must have exit genes");
        assertTrue(c.stopLoss() >= 0, "SL must be >= 0");
        assertTrue(c.takeProfit() >= 0, "TP must be >= 0");
        // Verify all genes have valid types
        for (Gene g : c.entryGenes()) {
            assertNotNull(g.indicatorType());
            assertTrue(g.period() >= 2);
        }
        for (Gene g : c.exitGenes()) {
            assertNotNull(g.indicatorType());
            assertTrue(g.period() >= 2);
        }
    }

    @Test
    void testCrossoverProducesValidChild() {
        Chromosome parent1 = new Chromosome(
            List.of(sma14Close, ema20Close),
            List.of(rsi14),
            30, 60
        );
        Chromosome parent2 = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.HIGH)),
            List.of(new Gene(Gene.IndicatorType.ATR, 10, Gene.Field.LOW),
                new Gene(Gene.IndicatorType.SMA, 5, Gene.Field.OPEN)),
            50, 100
        );
        Chromosome child = parent1.crossover(parent2);
        assertNotNull(child);
        assertFalse(child.entryGenes().isEmpty());
        assertFalse(child.exitGenes().isEmpty());
        // Child should have some genes from each parent
        assertTrue(child.entryGenes().size() >= 1);
        assertTrue(child.exitGenes().size() >= 1);
    }

    @Test
    void testMutationChangesGenes() {
        Chromosome c = new Chromosome(
            List.of(sma14Close, ema20Close),
            List.of(rsi14),
            30, 60
        );
        Chromosome original = new Chromosome(
            List.of(sma14Close, ema20Close),
            List.of(rsi14),
            30, 60
        );
        // Apply mutation many times; eventually something should change
        boolean changed = false;
        for (int i = 0; i < 100; i++) {
            c.mutate();
            if (!c.equals(original)) {
                changed = true;
                break;
            }
        }
        assertTrue(changed, "Mutation should change the chromosome within 100 attempts");
    }

    @Test
    void testCopyIsIndependent() {
        Chromosome original = Chromosome.random();
        Chromosome originalSnapshot = original.copy();
        Chromosome copy = original.copy();
        assertEquals(original, copy);
        
        // Mutate copy until it changes (to handle cases where mutation delta is 0 or clamped)
        boolean changed = false;
        for (int i = 0; i < 100; i++) {
            copy.mutate();
            if (!copy.equals(originalSnapshot)) {
                changed = true;
                break;
            }
        }
        assertTrue(changed, "Mutation should eventually change the copy");
        
        // Original should remain unchanged (independent of copy)
        assertEquals(originalSnapshot, original);
        assertNotEquals(original, copy);
    }

    @Test
    void testSetStopLossAndTakeProfit() {
        Chromosome c = new Chromosome(
            List.of(sma14Close),
            List.of(rsi14),
            10, 20
        );
        c.stopLoss(50);
        c.takeProfit(100);
        assertEquals(50, c.stopLoss());
        assertEquals(100, c.takeProfit());
    }
}
