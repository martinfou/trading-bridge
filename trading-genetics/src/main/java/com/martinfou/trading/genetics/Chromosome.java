package com.martinfou.trading.genetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a single trading strategy as a genetic chromosome.
 *
 * <p>A chromosome encodes the complete DNA of a strategy:
 * <ul>
 *   <li><b>Entry genes</b> — indicator conditions that trigger trade entries</li>
 *   <li><b>Exit genes</b>  — indicator conditions that trigger trade exits</li>
 *   <li><b>Stop-loss / take-profit</b> — risk management parameters in price offset</li>
 * </ul>
 *
 * <p>Chromosomes support crossover (mating) and mutation for use
 * in a genetic algorithm over successive generations.</p>
 */
public final class Chromosome {

    private final List<Gene> entryGenes;
    private final List<Gene> exitGenes;
    private int stopLoss;    // in price ticks / points
    private int takeProfit;  // in price ticks / points

    private static final int MIN_GENES = 1;
    private static final int MAX_GENES = 5;
    private static final int MAX_RISK = 500; // max SL/TP in price points

    /**
     * Creates a chromosome with the given entry and exit conditions.
     *
     * @param entryGenes  entry indicator genes (must not be empty)
     * @param exitGenes   exit indicator genes (must not be empty)
     * @param stopLoss    stop-loss offset in price points
     * @param takeProfit  take-profit offset in price points
     * @throws IllegalArgumentException if entry or exit lists are empty
     */
    public Chromosome(List<Gene> entryGenes, List<Gene> exitGenes, int stopLoss, int takeProfit) {
        if (entryGenes == null || entryGenes.isEmpty()) {
            throw new IllegalArgumentException("entryGenes must not be null or empty");
        }
        if (exitGenes == null || exitGenes.isEmpty()) {
            throw new IllegalArgumentException("exitGenes must not be null or empty");
        }
        this.entryGenes = new ArrayList<>(entryGenes);
        this.exitGenes = new ArrayList<>(exitGenes);
        this.stopLoss = Math.clamp(stopLoss, 0, MAX_RISK);
        this.takeProfit = Math.clamp(takeProfit, 0, MAX_RISK);
    }

    /**
     * Returns the entry indicator genes (conditions that trigger trade entry).
     *
     * @return unmodifiable list of entry genes
     */
    public List<Gene> entryGenes() {
        return List.copyOf(entryGenes);
    }

    /**
     * Returns the exit indicator genes (conditions that trigger trade exit).
     *
     * @return unmodifiable list of exit genes
     */
    public List<Gene> exitGenes() {
        return List.copyOf(exitGenes);
    }

    /**
     * Returns the stop-loss offset in price points.
     *
     * @return stop-loss value (0 = no stop-loss)
     */
    public int stopLoss() {
        return stopLoss;
    }

    /**
     * Sets the stop-loss offset in price points.
     *
     * @param stopLoss new stop-loss value (clamped to [0, 500])
     */
    public void stopLoss(int stopLoss) {
        this.stopLoss = Math.clamp(stopLoss, 0, MAX_RISK);
    }

    /**
     * Returns the take-profit offset in price points.
     *
     * @return take-profit value (0 = no take-profit)
     */
    public int takeProfit() {
        return takeProfit;
    }

    /**
     * Sets the take-profit offset in price points.
     *
     * @param takeProfit new take-profit value (clamped to [0, 500])
     */
    public void takeProfit(int takeProfit) {
        this.takeProfit = Math.clamp(takeProfit, 0, MAX_RISK);
    }

    /**
     * Performs single-point crossover with another chromosome to produce a child.
     *
     * <p>A random crossover point is chosen independently for entry and exit genes.
     * The child receives the first part from this chromosome and the remainder
     * from the other parent. Stop-loss and take-profit are averaged.</p>
     *
     * @param other the other parent chromosome
     * @return a new child {@link Chromosome}
     */
    public Chromosome crossover(Chromosome other) {
        Objects.requireNonNull(other, "other must not be null");
        var rng = ThreadLocalRandom.current();

        // Crossover entry genes at a random point
        // Ensure valid range: origin < bound
        int maxEntryPoint = Math.min(this.entryGenes.size(), other.entryGenes.size());
        int entryPoint = maxEntryPoint > 1 ? rng.nextInt(1, maxEntryPoint) : 1;
        List<Gene> childEntry = new ArrayList<>();
        childEntry.addAll(this.entryGenes.subList(0, entryPoint));
        childEntry.addAll(other.entryGenes.subList(entryPoint, other.entryGenes.size()));

        // Crossover exit genes at a random point
        int maxExitPoint = Math.min(this.exitGenes.size(), other.exitGenes.size());
        int exitPoint = maxExitPoint > 1 ? rng.nextInt(1, maxExitPoint) : 1;
        List<Gene> childExit = new ArrayList<>();
        childExit.addAll(this.exitGenes.subList(0, exitPoint));
        childExit.addAll(other.exitGenes.subList(exitPoint, other.exitGenes.size()));

        // Average risk parameters
        int childSl = (this.stopLoss + other.stopLoss) / 2;
        int childTp = (this.takeProfit + other.takeProfit) / 2;

        return new Chromosome(childEntry, childExit, childSl, childTp);
    }

    /**
     * Mutates this chromosome by modifying a random gene or risk parameter.
     *
     * <p>With equal probability, either a random entry gene, a random exit gene,
     * the stop-loss, or the take-profit is mutated.</p>
     */
    public void mutate() {
        var rng = ThreadLocalRandom.current();
        int choice = rng.nextInt(4);
        switch (choice) {
            case 0 -> {
                // Mutate a random entry gene
                if (!entryGenes.isEmpty()) {
                    entryGenes.get(rng.nextInt(entryGenes.size())).mutate();
                }
            }
            case 1 -> {
                // Mutate a random exit gene
                if (!exitGenes.isEmpty()) {
                    exitGenes.get(rng.nextInt(exitGenes.size())).mutate();
                }
            }
            case 2 -> {
                // Mutate stop-loss
                int delta = rng.nextInt(-20, 21);
                stopLoss = Math.clamp(stopLoss + delta, 0, MAX_RISK);
            }
            case 3 -> {
                // Mutate take-profit
                int delta = rng.nextInt(-20, 21);
                takeProfit = Math.clamp(takeProfit + delta, 0, MAX_RISK);
            }
            default -> throw new IllegalStateException("Unexpected mutation choice: " + choice);
        }
    }

    /**
     * Generates a random chromosome with random entry/exit genes and risk parameters.
     *
     * <p>The number of entry and exit genes varies randomly from
     * {@value #MIN_GENES} to {@value #MAX_GENES} each.</p>
     *
     * @return a new random {@link Chromosome}
     */
    public static Chromosome random() {
        var rng = ThreadLocalRandom.current();
        int entryCount = rng.nextInt(MIN_GENES, MAX_GENES + 1);
        List<Gene> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            entries.add(Gene.random());
        }

        int exitCount = rng.nextInt(MIN_GENES, MAX_GENES + 1);
        List<Gene> exits = new ArrayList<>(exitCount);
        for (int i = 0; i < exitCount; i++) {
            exits.add(Gene.random());
        }

        int sl = rng.nextInt(0, 101);
        int tp = rng.nextInt(0, 101);

        return new Chromosome(entries, exits, sl, tp);
    }

    /**
     * Creates a deep copy of this chromosome.
     *
     * @return a new chromosome with identical genes and risk parameters
     */
    public Chromosome copy() {
        List<Gene> entryCopy = entryGenes.stream()
            .map(g -> new Gene(g.indicatorType(), g.period(), g.field()))
            .toList();
        List<Gene> exitCopy = exitGenes.stream()
            .map(g -> new Gene(g.indicatorType(), g.period(), g.field()))
            .toList();
        return new Chromosome(entryCopy, exitCopy, stopLoss, takeProfit);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Chromosome that)) return false;
        return stopLoss == that.stopLoss
            && takeProfit == that.takeProfit
            && entryGenes.equals(that.entryGenes)
            && exitGenes.equals(that.exitGenes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryGenes, exitGenes, stopLoss, takeProfit);
    }

    @Override
    public String toString() {
        return "Chromosome{" +
            "entry=" + entryGenes +
            ", exit=" + exitGenes +
            ", SL=" + stopLoss +
            ", TP=" + takeProfit +
            '}';
    }
}
