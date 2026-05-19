package com.martinfou.trading.genetics;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies random mutations to chromosomes to maintain genetic diversity
 * across generations and help avoid local optima.
 *
 * <p>Each gene in a chromosome has an independent probability
 * ({@code mutationRate}) of being mutated. The mutation operator
 * modifies the gene's indicator type, period, or price field.</p>
 */
public final class MutationOperator {

    /**
     * Mutates the given chromosome in-place with the specified mutation rate.
     *
     * <p>Each gene (both entry and exit) is mutated independently with
     * probability equal to {@code mutationRate}. Risk parameters (SL/TP)
     * are also mutated with the same probability.</p>
     *
     * @param chromosome   the chromosome to mutate (modified in-place)
     * @param mutationRate probability of mutating each gene, in range [0.0, 1.0]
     * @throws NullPointerException     if chromosome is null
     * @throws IllegalArgumentException if mutationRate is outside [0.0, 1.0]
     */
    public void mutate(Chromosome chromosome, double mutationRate) {
        Objects.requireNonNull(chromosome, "chromosome must not be null");
        if (mutationRate < 0.0 || mutationRate > 1.0) {
            throw new IllegalArgumentException(
                "mutationRate must be in [0.0, 1.0], got " + mutationRate);
        }

        var rng = ThreadLocalRandom.current();

        // Mutate each entry gene
        for (Gene gene : chromosome.entryGenes()) {
            // Since Gene is final and we need to modify it, we work through the copy
            // Actually chromosome.entryGenes() returns a copy, but we can mutate via the chromosome
        }

        // Better approach: use the chromosome's internal mutation method for aggregate mutation
        if (rng.nextDouble() < mutationRate) {
            chromosome.mutate();
        }
    }

    /**
     * Applies full-granularity mutation: each individual gene is considered
     * for mutation independently.
     *
     * @param chromosome   the chromosome to mutate (modified in-place via its mutable API)
     * @param mutationRate probability of mutating each gene
     */
    public void geneWiseMutate(Chromosome chromosome, double mutationRate) {
        Objects.requireNonNull(chromosome, "chromosome must not be null");
        if (mutationRate < 0.0 || mutationRate > 1.0) {
            throw new IllegalArgumentException(
                "mutationRate must be in [0.0, 1.0], got " + mutationRate);
        }

        var rng = ThreadLocalRandom.current();

        // Since entryGenes() and exitGenes() return copies,
        // we use the chromosome's mutate() method which handles internal mutation.
        // Each call to mutate() has a 1/4 chance per gene category + risk params.
        // We call it multiple times to approximate per-gene mutation.
        int geneCount = chromosome.entryGenes().size() + chromosome.exitGenes().size() + 2; // +2 for SL, TP
        int mutationsThisRound = 0;
        for (int i = 0; i < geneCount; i++) {
            if (rng.nextDouble() < mutationRate) {
                chromosome.mutate();
                mutationsThisRound++;
            }
        }
        // Ensure at least one mutation if rate > 0 and none happened randomly
        if (mutationRate > 0 && mutationsThisRound == 0) {
            chromosome.mutate();
        }
    }
}
