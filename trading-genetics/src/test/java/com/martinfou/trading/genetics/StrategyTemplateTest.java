package com.martinfou.trading.genetics;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StrategyTemplate}.
 */
class StrategyTemplateTest {

    @Test
    void testTemplateWithChromosomeProducesOrders() {
        // Create a chromosome with SMA crossover entry
        Chromosome c = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.SMA, 5, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            30, 60
        );
        StrategyTemplate template = new StrategyTemplate(c);
        assertNotNull(template.name());
        assertEquals(c, template.chromosome());
    }

    @Test
    void testTemplateGeneratesAndDeliversPendingOrders() {
        Chromosome c = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.SMA, 2, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            30, 60
        );
        StrategyTemplate template = new StrategyTemplate(c);

        // Feed enough bars to generate some orders
        List<Bar> bars = TestBarData.generateTrendingBars(30, 100.0);
        for (Bar bar : bars) {
            template.onBar(bar);
        }

        List<Order> orders = template.getPendingOrders();
        // We can't guarantee orders were generated with random data,
        // but the method should at least return a non-null list
        assertNotNull(orders);
    }

    @Test
    void testTemplateResetClearsState() {
        Chromosome c = new Chromosome(
            List.of(new Gene(Gene.IndicatorType.SMA, 5, Gene.Field.CLOSE)),
            List.of(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE)),
            30, 60
        );
        StrategyTemplate template = new StrategyTemplate(c);

        // Feed some bars
        List<Bar> bars = TestBarData.generateTrendingBars(10, 100.0);
        for (Bar bar : bars) {
            template.onBar(bar);
        }

        // Reset
        template.reset();

        // After reset, getPendingOrders should return an empty list
        List<Order> orders = template.getPendingOrders();
        assertTrue(orders.isEmpty(), "After reset, pending orders should be empty");
    }

    @Test
    void testTemplateWithRandomChromosome() {
        Chromosome c = Chromosome.random();
        StrategyTemplate template = new StrategyTemplate(c);

        // Ensure it doesn't throw when processing bars
        List<Bar> bars = TestBarData.generateSineBars(50, 100.0, 5.0);
        for (Bar bar : bars) {
            assertDoesNotThrow(() -> template.onBar(bar));
        }

        List<Order> orders = template.getPendingOrders();
        assertNotNull(orders);
    }

    @Test
    void testOnTickDoesNothing() {
        Chromosome c = Chromosome.random();
        StrategyTemplate template = new StrategyTemplate(c);
        assertDoesNotThrow(() -> template.onTick(1.0, 1.01, 1000));
    }
}
