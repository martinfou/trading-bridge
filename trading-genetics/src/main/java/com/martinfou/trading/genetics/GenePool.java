package com.martinfou.trading.genetics;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Defines the pool of available trading indicators as mutable genes.
 *
 * <p>The gene pool provides default starting genes that serve as the
 * foundation for generating genetic diversity in the population.
 * It also provides mutation utilities for modifying individual genes.</p>
 */
public final class GenePool {

    private GenePool() {
        // Utility class
    }

    /**
     * Returns the default set of starting genes for the genetic algorithm.
     *
     * <p>These represent common technical indicators with sensible default periods:</p>
     * <ul>
     *   <li>SMA(14, CLOSE)</li>
     *   <li>EMA(14, CLOSE)</li>
     *   <li>RSI(14)</li>
     *   <li>ATR(14)</li>
     *   <li>ADX(14)</li>
     * </ul>
     *
     * @return immutable list of default genes
     */
    public static List<Gene> getDefaultGenes() {
        return List.of(
            new Gene(Gene.IndicatorType.SMA, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.EMA, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.ATR, 14, Gene.Field.CLOSE),
            new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE)
        );
    }

    /**
     * Mutates a copy of the given gene by randomly modifying one parameter.
     *
     * <p>The mutation modifies the period by ±20%, changes the indicator type,
     * or changes the price field, selected with equal probability.</p>
     *
     * @param g the original gene to mutate (unchanged)
     * @return a new mutated {@link Gene}
     */
    public static Gene mutate(Gene g) {
        var rng = ThreadLocalRandom.current();
        int choice = rng.nextInt(3);
        return switch (choice) {
            case 0 -> {
                // Mutate period by ±20%
                int delta = (int) (g.period() * 0.2 * (rng.nextBoolean() ? 1 : -1));
                if (delta == 0) delta = rng.nextBoolean() ? 2 : -2;
                var mutated = new Gene(g.indicatorType(), Math.clamp(g.period() + delta, 2, 200), g.field());
                yield mutated;
            }
            case 1 -> {
                // Change indicator type
                Gene.IndicatorType[] types = Gene.IndicatorType.values();
                Gene.IndicatorType t;
                do {
                    t = types[rng.nextInt(types.length)];
                } while (t == g.indicatorType());
                yield new Gene(t, g.period(), g.field());
            }
            case 2 -> {
                // Change field
                Gene.Field[] fields = Gene.Field.values();
                Gene.Field f;
                do {
                    f = fields[rng.nextInt(fields.length)];
                } while (f == g.field());
                yield new Gene(g.indicatorType(), g.period(), f);
            }
            default -> throw new IllegalStateException("Unexpected mutation choice: " + choice);
        };
    }
}
