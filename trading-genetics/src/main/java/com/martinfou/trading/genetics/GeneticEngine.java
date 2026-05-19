package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * Genetic algorithm engine that evolves trading strategies through
 * selection, crossover, and mutation over successive generations.
 *
 * <p>Conceptually similar to StrategyQuant: the engine starts with a random
 * population of chromosomes (each encoding a trading strategy), backtests
 * each one, evaluates fitness, and breeds the next generation from the
 * fittest individuals.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Initialize population with random chromosomes</li>
 *   <li>For each generation (default max 50):
 *     <ul>
 *       <li>Backtest each chromosome against historical bar data</li>
 *       <li>Calculate fitness = Sharpe Ratio (via {@code PerformanceMetrics})</li>
 *       <li>Select parents via tournament selection</li>
 *       <li>Apply crossover and mutation to produce offspring</li>
 *       <li>Preserve top 10% as elite</li>
 *     </ul>
 *   </li>
 *   <li>Return the fittest chromosome found across all generations</li>
 * </ol>
 *
 * <p>Backtests are parallelised across Virtual Threads (Java 21) for
 * maximum throughput.</p>
 */
public final class GeneticEngine {

    private static final Logger log = LoggerFactory.getLogger(GeneticEngine.class);

    /** Default population size. */
    public static final int DEFAULT_POPULATION_SIZE = 50;

    /** Default maximum number of generations. */
    public static final int DEFAULT_MAX_GENERATIONS = 50;

    /** Default mutation rate (probability per gene). */
    public static final double DEFAULT_MUTATION_RATE = 0.1;

    /** Default crossover rate (probability of crossover per mating pair). */
    public static final double DEFAULT_CROSSOVER_RATE = 0.8;

    /** Default elitism proportion (fraction of top individuals preserved). */
    public static final double DEFAULT_ELITISM_RATIO = 0.10;

    /** Default initial capital for backtests. */
    public static final double DEFAULT_INITIAL_CAPITAL = 100_000.0;

    private final List<Bar> bars;
    private final int populationSize;
    private final int maxGenerations;
    private final double mutationRate;
    private final double crossoverRate;
    private final double elitismRatio;
    private final double initialCapital;
    private final SelectionStrategy selectionStrategy;
    private final CrossoverOperator crossoverOperator;
    private final MutationOperator mutationOperator;
    private final Random rng = new Random();

    /**
     * Creates a genetic engine with default configuration.
     *
     * @param bars historical bar data for backtesting strategies
     * @throws NullPointerException if bars is null or empty
     */
    public GeneticEngine(List<Bar> bars) {
        this(bars, DEFAULT_POPULATION_SIZE, DEFAULT_MAX_GENERATIONS,
            DEFAULT_MUTATION_RATE, DEFAULT_CROSSOVER_RATE,
            DEFAULT_ELITISM_RATIO, DEFAULT_INITIAL_CAPITAL,
            new SelectionStrategy.TournamentSelection(3));
    }

    /**
     * Creates a fully configurable genetic engine.
     *
     * @param bars                historical bar data
     * @param populationSize       number of chromosomes per generation
     * @param maxGenerations       maximum number of generations to evolve
     * @param mutationRate         probability of mutation per gene
     * @param crossoverRate        probability of crossover per mating pair
     * @param elitismRatio         fraction of top individuals preserved unchanged
     * @param initialCapital       initial capital for backtests
     * @param selectionStrategy    strategy for selecting parents
     * @throws NullPointerException     if bars or selectionStrategy is null
     * @throws IllegalArgumentException if population size or other params are invalid
     */
    public GeneticEngine(List<Bar> bars, int populationSize, int maxGenerations,
                          double mutationRate, double crossoverRate,
                          double elitismRatio, double initialCapital,
                          SelectionStrategy selectionStrategy) {
        this.bars = Objects.requireNonNull(bars, "bars must not be null");
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be empty");
        }
        if (populationSize < 4) {
            throw new IllegalArgumentException("populationSize must be >= 4, got " + populationSize);
        }
        if (maxGenerations < 1) {
            throw new IllegalArgumentException("maxGenerations must be >= 1, got " + maxGenerations);
        }
        if (mutationRate < 0.0 || mutationRate > 1.0) {
            throw new IllegalArgumentException("mutationRate must be in [0.0, 1.0], got " + mutationRate);
        }
        if (crossoverRate < 0.0 || crossoverRate > 1.0) {
            throw new IllegalArgumentException("crossoverRate must be in [0.0, 1.0], got " + crossoverRate);
        }
        if (elitismRatio < 0.0 || elitismRatio > 0.5) {
            throw new IllegalArgumentException("elitismRatio must be in [0.0, 0.5], got " + elitismRatio);
        }
        this.populationSize = populationSize;
        this.maxGenerations = maxGenerations;
        this.mutationRate = mutationRate;
        this.crossoverRate = crossoverRate;
        this.elitismRatio = elitismRatio;
        this.initialCapital = initialCapital;
        this.selectionStrategy = Objects.requireNonNull(selectionStrategy, "selectionStrategy must not be null");
        this.crossoverOperator = new CrossoverOperator();
        this.mutationOperator = new MutationOperator();
    }

    /**
     * Runs the full genetic optimisation loop and returns the best result.
     *
     * @return the best generation result found (chromosome + fitness + generation)
     */
    public GenerationResult run() {
        log.info("Starting genetic optimisation: pop={}, gens={}, mutation={}, crossover={}, elitism={}",
            populationSize, maxGenerations, mutationRate, crossoverRate, elitismRatio);

        // Step 1: Generate initial population
        List<Chromosome> population = new ArrayList<>(populationSize);
        for (int i = 0; i < populationSize; i++) {
            population.add(Chromosome.random());
        }

        Chromosome bestEver = null;
        double bestFitnessEver = Double.NEGATIVE_INFINITY;
        int bestGeneration = -1;
        int noImprovementCount = 0;
        int eliteCount = Math.max(1, (int) (populationSize * elitismRatio));

        for (int generation = 0; generation < maxGenerations; generation++) {
            log.debug("Generation {}/{}", generation + 1, maxGenerations);

            // Step 2a: Backtest all chromosomes in parallel
            List<Double> fitness = evaluateFitness(population);

            // Step 2b: Track best individual
            double genBestFitness = Double.NEGATIVE_INFINITY;
            int genBestIdx = -1;
            for (int i = 0; i < fitness.size(); i++) {
                if (fitness.get(i) > genBestFitness) {
                    genBestFitness = fitness.get(i);
                    genBestIdx = i;
                }
            }

            log.info("Generation {}: best fitness = {}.4f", generation + 1, genBestFitness);

            if (genBestFitness > bestFitnessEver) {
                bestFitnessEver = genBestFitness;
                bestEver = population.get(genBestIdx).copy();
                bestGeneration = generation;
                noImprovementCount = 0;
            } else {
                noImprovementCount++;
            }

            // Early stopping if no improvement for 10 generations
            if (noImprovementCount >= 10) {
                log.info("Early stopping at generation {} (no improvement for 10 generations)", generation + 1);
                break;
            }

            // Step 2c: Generate next population
            population = createNextGeneration(population, fitness, eliteCount);

            // Step 2d (in createNextGeneration): Elitism handled inside
        }

        log.info("Genetic optimisation complete. Best fitness: {}.4f at generation {}",
            bestFitnessEver, bestGeneration + 1);

        return new GenerationResult(bestEver, bestFitnessEver, bestGeneration);
    }

    /**
     * Evaluates fitness for all chromosomes in the population using parallel backtests.
     *
     * @param population current population of chromosomes
     * @return list of fitness values (Sharpe Ratio) in same order
     */
    private List<Double> evaluateFitness(List<Chromosome> population) {
        // Use Java 21 Virtual Threads for parallel backtesting
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Double>> futures = new ArrayList<>(population.size());

            for (Chromosome chromosome : population) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        Strategy strategy = new StrategyTemplate(chromosome);
                        BacktestResult result = runBacktest(strategy);
                        return extractFitness(result);
                    } catch (Exception e) {
                        log.warn("Backtest failed for chromosome {}: {}", chromosome, e.getMessage());
                        return Double.NEGATIVE_INFINITY;
                    }
                }, executor));
            }

            // Wait for all backtests to complete
            return futures.stream()
                .map(CompletableFuture::join)
                .toList();
        }
    }

    /**
     * Runs a single backtest for the given strategy.
     */
    private BacktestResult runBacktest(Strategy strategy) {
        BacktestEngine engine = new BacktestEngine(strategy, bars, initialCapital);
        // Apply realistic cost assumptions
        engine.withCommissionFixed(0.0)
            .withCommissionPct(0.0007);  // 0.7 pip commission
        return engine.run();
    }

    /**
     * Extracts the fitness value from a backtest result.
     *
     * <p>Fitness is the Sharpe Ratio. If the Sharpe Ratio is non-positive,
     * we fall back to a combination of total return and profit factor
     * to avoid zero/negative fitness issues in selection.</p>
     */
    private double extractFitness(BacktestResult result) {
        double sharpe = result.sharpeRatio();
        if (sharpe > 0) {
            return sharpe;
        }

        // Fallback: combine return and profit factor for strategies with negative Sharpe
        double totalReturn = result.totalReturnPct() / 100.0;
        double profitFactor = result.profitFactor();
        double winRate = result.winRatePct() / 100.0;

        // Weighted composite: return + profit factor contribution + win rate bonus
        double fitness = totalReturn * 0.5
            + (profitFactor > 1 ? profitFactor * 0.3 : 0.0)
            + winRate * 0.2;

        // Floor at a small positive value to avoid elimination of temporarily underperforming strategies
        return Math.max(fitness, 0.001);
    }

    /**
     * Creates the next generation through selection, crossover, mutation, and elitism.
     */
    private List<Chromosome> createNextGeneration(List<Chromosome> population,
                                                   List<Double> fitness,
                                                   int eliteCount) {
        int popSize = population.size();
        List<Chromosome> nextGen = new ArrayList<>(popSize);

        // Elitism: preserve the top individuals unchanged
        List<Integer> sortedIndices = IntStream.range(0, popSize)
            .boxed()
            .sorted(Comparator.comparingDouble((Integer i) -> fitness.get(i)).reversed())
            .toList();

        for (int i = 0; i < eliteCount; i++) {
            nextGen.add(population.get(sortedIndices.get(i)).copy());
        }

        // Fill the rest of the population through selection, crossover, and mutation
        while (nextGen.size() < popSize) {
            // Select two parents
            Chromosome parent1 = selectionStrategy.select(population, fitness);
            Chromosome parent2 = selectionStrategy.select(population, fitness);

            Chromosome child;
            if (rng.nextDouble() < crossoverRate) {
                child = crossoverOperator.singlePointCrossover(parent1, parent2);
            } else {
                child = parent1.copy();
            }

            // Apply mutation
            mutationOperator.geneWiseMutate(child, mutationRate);

            nextGen.add(child);
        }

        return nextGen;
    }

    // ---------------------------------------------------------------
    //  Generation Result
    // ---------------------------------------------------------------

    /**
     * Result of one generation or the complete genetic optimisation run.
     *
     * @param best       the best chromosome found
     * @param fitness    fitness value of the best chromosome
     * @param generation the generation index (0-based) where the best was found
     */
    public record GenerationResult(Chromosome best, double fitness, int generation) {

        /**
         * Returns a human-readable summary of this result.
         *
         * @return formatted summary string
         */
        public String summary() {
            return String.format(
                "Generation %d | Fitness: %.4f | Best: %s",
                generation + 1, fitness, best
            );
        }
    }
}
