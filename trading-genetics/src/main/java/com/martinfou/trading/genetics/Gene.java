package com.martinfou.trading.genetics;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A mutable gene representing a trading indicator within a genetic algorithm chromosome.
 *
 * <p>Each gene defines an indicator type (e.g. SMA, RSI), a period parameter,
 * and the price field the indicator operates on. The gene can be mutated
 * to introduce genetic diversity across the population.</p>
 *
 * <p>This is an explicit class (not a Java record) because the {@code period}
 * field must be mutable for mutation operations.</p>
 */
public final class Gene {

    /** Supported technical indicator types for genetic strategies. */
    public enum IndicatorType {
        SMA,    // Simple Moving Average
        EMA,    // Exponential Moving Average
        RSI,    // Relative Strength Index
        ATR,    // Average True Range
        ADX     // Average Directional Index
    }

    /** Price fields that indicators can operate on. */
    public enum Field {
        CLOSE,
        OPEN,
        HIGH,
        LOW
    }

    private IndicatorType indicatorType;
    private int period;
    private Field field;

    /**
     * Creates a new gene with the given indicator configuration.
     *
     * @param indicatorType the indicator type (SMA, EMA, RSI, ATR, ADX)
     * @param period        the period parameter for the indicator
     * @param field         the price field (ignored for RSI/ATR/ADX but stored for consistency)
     */
    public Gene(IndicatorType indicatorType, int period, Field field) {
        this.indicatorType = Objects.requireNonNull(indicatorType, "indicatorType");
        this.field = Objects.requireNonNull(field, "field");
        setPeriod(period);
    }

    /**
     * Returns the indicator type.
     *
     * @return {@link IndicatorType}
     */
    public IndicatorType indicatorType() {
        return indicatorType;
    }

    /**
     * Sets the indicator type.
     *
     * @param indicatorType new indicator type
     */
    public void indicatorType(IndicatorType indicatorType) {
        this.indicatorType = Objects.requireNonNull(indicatorType, "indicatorType");
    }

    /**
     * Returns the period parameter for the indicator.
     *
     * @return period value (always ≥ 2)
     */
    public int period() {
        return period;
    }

    /**
     * Sets the period parameter. Clamped to the range [2, 200].
     *
     * @param period desired period value
     */
    public void setPeriod(int period) {
        this.period = Math.clamp(period, 2, 200);
    }

    /**
     * Returns the price field the indicator operates on.
     *
     * @return {@link Field}
     */
    public Field field() {
        return field;
    }

    /**
     * Sets the price field.
     *
     * @param field price field
     */
    public void field(Field field) {
        this.field = Objects.requireNonNull(field, "field");
    }

    /**
     * Mutates this gene by randomly modifying one of its parameters.
     *
     * <p>With equal probability, either the period is adjusted by ±20%,
     * the indicator type is changed to a different random type,
     * or the field is changed to a different random field.</p>
     */
    public void mutate() {
        var rng = ThreadLocalRandom.current();
        int choice = rng.nextInt(3);
        switch (choice) {
            case 0 -> {
                // Mutate period by ±20%
                int delta = (int) (period * 0.2 * (rng.nextBoolean() ? 1 : -1));
                if (delta == 0) delta = rng.nextBoolean() ? 2 : -2;
                setPeriod(period + delta);
            }
            case 1 -> {
                // Change indicator type to a different one
                IndicatorType[] types = IndicatorType.values();
                IndicatorType current = indicatorType;
                while (indicatorType == current) {
                    indicatorType = types[rng.nextInt(types.length)];
                }
            }
            case 2 -> {
                // Change field to a different one
                Field[] fields = Field.values();
                Field current = field;
                while (field == current) {
                    field = fields[rng.nextInt(fields.length)];
                }
            }
            default -> throw new IllegalStateException("Unexpected mutation choice: " + choice);
        }
    }

    /**
     * Creates a random gene with random indicator type, period, and field.
     *
     * @return a new random {@link Gene}
     */
    public static Gene random() {
        var rng = ThreadLocalRandom.current();
        IndicatorType[] types = IndicatorType.values();
        IndicatorType type = types[rng.nextInt(types.length)];
        int period = rng.nextInt(2, 200);
        Field[] fields = Field.values();
        Field field = fields[rng.nextInt(fields.length)];
        return new Gene(type, period, field);
    }

    // ---------------------------------------------------------------
    //  Object overrides
    // ---------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Gene gene)) return false;
        return period == gene.period
            && indicatorType == gene.indicatorType
            && field == gene.field;
    }

    @Override
    public int hashCode() {
        return Objects.hash(indicatorType, period, field);
    }

    @Override
    public String toString() {
        return indicatorType + "(" + period + ", " + field + ")";
    }
}
