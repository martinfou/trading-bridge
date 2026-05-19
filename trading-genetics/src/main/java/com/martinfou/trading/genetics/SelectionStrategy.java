package com.martinfou.trading.genetics;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Interface for selecting chromosomes from a population based on fitness scores.
 *
 * <p>Selection strategies determine which parent chromosomes survive to
 * reproduce and form the next generation.</p>
 */
@FunctionalInterface
public interface SelectionStrategy {

    /**
     * Selects a chromosome from the population using the implemented strategy.
     *
     * @param population the current generation's chromosomes
     * @param fitness    fitness scores corresponding to each chromosome (same order)
     * @return a selected chromosome (may be the same object as in the population)
     * @throws IllegalArgumentException if population is empty or sizes don't match
     */
    Chromosome select(List<Chromosome> population, List<Double> fitness);

    // ---------------------------------------------------------------
    //  Tournament Selection
    // ---------------------------------------------------------------

    /**
     * Tournament selection strategy.
     *
     * <p>Picks a random subset of {@code tournamentSize} individuals and returns
     * the one with the highest fitness. Larger tournament sizes increase selection
     * pressure.</p>
     */
    final class TournamentSelection implements SelectionStrategy {

        private final int tournamentSize;

        /**
         * Creates a tournament selection with the given tournament size.
         *
         * @param tournamentSize number of individuals competing per round (default 3)
         * @throws IllegalArgumentException if size is less than 1
         */
        public TournamentSelection(int tournamentSize) {
            if (tournamentSize < 1) {
                throw new IllegalArgumentException("tournamentSize must be >= 1, got " + tournamentSize);
            }
            this.tournamentSize = tournamentSize;
        }

        /**
         * Creates a tournament selection with default tournament size of 3.
         */
        public TournamentSelection() {
            this(3);
        }

        @Override
        public Chromosome select(List<Chromosome> population, List<Double> fitness) {
            validate(population, fitness);
            var rng = ThreadLocalRandom.current();
            int size = population.size();
            int actualTourney = Math.min(tournamentSize, size);

            int bestIdx = rng.nextInt(size);
            double bestFitness = fitness.get(bestIdx);

            for (int i = 1; i < actualTourney; i++) {
                int idx = rng.nextInt(size);
                double f = fitness.get(idx);
                if (f > bestFitness) {
                    bestFitness = f;
                    bestIdx = idx;
                }
            }
            return population.get(bestIdx);
        }

        /**
         * Returns the tournament size.
         *
         * @return tournament size
         */
        public int tournamentSize() {
            return tournamentSize;
        }
    }

    // ---------------------------------------------------------------
    //  Roulette Wheel Selection
    // ---------------------------------------------------------------

    /**
     * Roulette wheel (fitness-proportionate) selection strategy.
     *
     * <p>Individuals are selected with probability proportional to their fitness.
     * Higher fitness means higher chance of selection. All fitness values are
     * shifted to be non-negative before selection.</p>
     */
    final class RouletteWheelSelection implements SelectionStrategy {

        @Override
        public Chromosome select(List<Chromosome> population, List<Double> fitness) {
            validate(population, fitness);

            // Shift fitness to ensure non-negative values
            double min = Double.MAX_VALUE;
            for (double f : fitness) {
                if (f < min) min = f;
            }
            double shift = min < 0 ? -min + 0.001 : 0.001;

            double totalWeight = 0.0;
            double[] weights = new double[fitness.size()];
            for (int i = 0; i < fitness.size(); i++) {
                weights[i] = fitness.get(i) + shift;
                totalWeight += weights[i];
            }

            var rng = ThreadLocalRandom.current();
            double pick = rng.nextDouble() * totalWeight;
            double cumulative = 0.0;
            for (int i = 0; i < weights.length; i++) {
                cumulative += weights[i];
                if (cumulative >= pick) {
                    return population.get(i);
                }
            }
            return population.getLast();
        }
    }

    // ---------------------------------------------------------------
    //  Validation helper
    // ---------------------------------------------------------------

    private static void validate(List<Chromosome> population, List<Double> fitness) {
        if (population == null || population.isEmpty()) {
            throw new IllegalArgumentException("population must not be null or empty");
        }
        if (fitness == null || fitness.size() != population.size()) {
            throw new IllegalArgumentException(
                "fitness list size (" + (fitness == null ? 0 : fitness.size())
                    + ") must match population size (" + population.size() + ")");
        }
    }
}
