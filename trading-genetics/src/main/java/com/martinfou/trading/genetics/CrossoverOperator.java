package com.martinfou.trading.genetics;

import java.util.Objects;

/**
 * Performs single-point crossover between two parent chromosomes to produce offspring.
 *
 * <p>Crossover is a key genetic operator that combines the DNA of two parents
 * to create genetically diverse children for the next generation.</p>
 */
public final class CrossoverOperator {

    /**
     * Performs single-point crossover between two parent chromosomes.
     *
     * <p>A single crossover point is chosen randomly for both entry and exit genes.
     * The child receives genes before the crossover point from parent1 and genes
     * after the point from parent2. Risk parameters (SL/TP) are averaged.</p>
     *
     * @param parent1 the first parent chromosome
     * @param parent2 the second parent chromosome
     * @return a new child {@link Chromosome} combining both parents' DNA
     * @throws NullPointerException if either parent is null
     */
    public Chromosome singlePointCrossover(Chromosome parent1, Chromosome parent2) {
        Objects.requireNonNull(parent1, "parent1 must not be null");
        Objects.requireNonNull(parent2, "parent2 must not be null");
        return parent1.crossover(parent2);
    }
}
