package com.martinfou.trading.genetics;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A strategy template that converts a {@link Chromosome} into an executable
 * trading strategy compatible with the {@link BacktestEngine}.
 *
 * <p>The template evaluates the chromosome's entry and exit indicator conditions
 * on each bar and generates buy/sell orders accordingly. Simple moving average
 * crossover logic is used for demonstration; more sophisticated logic can
 * be plugged in via the chromosome's gene configuration.</p>
 *
 * <p><b>Strategy Logic:</b></p>
 * <ul>
 *   <li><b>Entry condition:</b> When the first entry gene's indicator value crosses
 *       above the second entry gene's indicator value (or is above a baseline),
 *       a BUY order is generated.</li>
 *   <li><b>Exit condition:</b> When any exit condition triggers (first exit gene
 *       crosses below second exit gene), a SELL order closes the position.</li>
 *   <li><b>Risk management:</b> Stop-loss and take-profit offsets from the
 *       chromosome are applied to all orders.</li>
 * </ul>
 */
public final class StrategyTemplate implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(StrategyTemplate.class);

    private final Chromosome chromosome;
    private final String name;
    private final Queue<Order> pendingOrders = new ConcurrentLinkedQueue<>();

    // Internal state for cross-bar indicator calculations
    private final Map<String, SimpleMovingAverage> entrySmaCache = new HashMap<>();
    private final Map<String, SimpleMovingAverage> exitSmaCache = new HashMap<>();
    private final Map<String, RsiValue> entryRsiCache = new HashMap<>();
    private final Map<String, RsiValue> exitRsiCache = new HashMap<>();
    private final Map<String, AtrValue> entryAtrCache = new HashMap<>();
    private final Map<String, AtrValue> exitAtrCache = new HashMap<>();

    private Bar lastBar;
    private boolean hasPosition;

    // Thresholds for indicator comparisons
    private static final double RSI_OVERBOUGHT = 70.0;
    private static final double RSI_OVERSOLD = 30.0;
    private static final double ADX_THRESHOLD = 25.0;

    /**
     * Creates a new strategy template for the given chromosome.
     *
     * @param chromosome the chromosome encoding this strategy's logic
     */
    public StrategyTemplate(Chromosome chromosome) {
        this.chromosome = Objects.requireNonNull(chromosome, "chromosome must not be null");
        this.name = "GeneticStrategy-" + Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Returns the chromosome driving this strategy.
     *
     * @return the underlying chromosome
     */
    public Chromosome chromosome() {
        return chromosome;
    }

    /**
     * Returns the name of this strategy.
     *
     * @return strategy name
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Called on each new bar. Evaluates the chromosome's entry/exit conditions
     * and generates orders.
     *
     * @param bar the current bar
     */
    @Override
    public void onBar(Bar bar) {
        this.lastBar = bar;

        // Update indicator caches
        updateIndicators(bar);

        if (!hasPosition) {
            // Check entry conditions
            if (evaluateEntryConditions(bar)) {
                double price = bar.close();
                Order order = new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 1.0, price);

                // Apply stop-loss and take-profit from chromosome
                if (chromosome.stopLoss() > 0) {
                    order.withStopLoss(price - chromosome.stopLoss() * 0.0001);
                }
                if (chromosome.takeProfit() > 0) {
                    order.withTakeProfit(price + chromosome.takeProfit() * 0.0001);
                }

                pendingOrders.add(order);
                hasPosition = true;
                log.debug("Generated BUY order at {}", price);
            }
        } else {
            // Check exit conditions
            if (evaluateExitConditions(bar)) {
                Order order = new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 1.0, bar.close());
                pendingOrders.add(order);
                hasPosition = false;
                log.debug("Generated SELL order at {}", bar.close());
            }
        }
    }

    /**
     * Tick handler (not used for genetic strategies which operate on bars).
     */
    @Override
    public void onTick(double bid, double ask, long volume) {
        // Bar-based strategies do not use tick data
    }

    /**
     * Returns all pending orders generated since the last check.
     *
     * @return list of pending orders
     */
    @Override
    public List<Order> getPendingOrders() {
        List<Order> orders = new ArrayList<>(pendingOrders);
        pendingOrders.clear();
        return orders;
    }

    /**
     * Resets the strategy to its initial state for a fresh backtest run.
     */
    @Override
    public void reset() {
        pendingOrders.clear();
        entrySmaCache.clear();
        exitSmaCache.clear();
        entryRsiCache.clear();
        exitRsiCache.clear();
        entryAtrCache.clear();
        exitAtrCache.clear();
        hasPosition = false;
        lastBar = null;
    }

    // ---------------------------------------------------------------
    //  Indicator computation helpers
    // ---------------------------------------------------------------

    /**
     * Updates all indicator caches with the latest bar data.
     */
    private void updateIndicators(Bar bar) {
        for (Gene gene : chromosome.entryGenes()) {
            updateIndicatorForGene(gene, bar, entrySmaCache, entryRsiCache, entryAtrCache);
        }
        for (Gene gene : chromosome.exitGenes()) {
            updateIndicatorForGene(gene, bar, exitSmaCache, exitRsiCache, exitAtrCache);
        }
    }

    private void updateIndicatorForGene(Gene gene, Bar bar,
                                         Map<String, SimpleMovingAverage> smaCache,
                                         Map<String, RsiValue> rsiCache,
                                         Map<String, AtrValue> atrCache) {
        String key = geneKey(gene);
        switch (gene.indicatorType()) {
            case SMA, EMA -> {
                SimpleMovingAverage sma = smaCache.computeIfAbsent(key,
                    k -> new SimpleMovingAverage(gene.period()));
                double price = getFieldValue(bar, gene.field());
                sma.add(price);
            }
            case RSI -> {
                RsiValue rsi = rsiCache.computeIfAbsent(key,
                    k -> new RsiValue(gene.period()));
                rsi.add(bar.close());
            }
            case ATR -> {
                AtrValue atr = atrCache.computeIfAbsent(key,
                    k -> new AtrValue(gene.period()));
                atr.add(bar);
            }
            case ADX -> {
                // ADX requires high/low/close; simplified implementation
                RsiValue rsi = rsiCache.computeIfAbsent(key + "_adx",
                    k -> new RsiValue(gene.period()));
                rsi.add(bar.close());
            }
        }
    }

    /**
     * Evaluates whether the entry conditions encoded in the chromosome are met.
     */
    private boolean evaluateEntryConditions(Bar bar) {
        List<Gene> entries = chromosome.entryGenes();
        if (entries.isEmpty()) return false;

        // Primary entry logic: use the first two entry genes as a crossover pair
        Gene primary = entries.get(0);
        double value = getIndicatorValue(primary, entrySmaCache, entryRsiCache, entryAtrCache);

        if (entries.size() >= 2) {
            Gene secondary = entries.get(1);
            double secondaryValue = getIndicatorValue(secondary, entrySmaCache, entryRsiCache, entryAtrCache);
            return value > secondaryValue;
        }

        // Single entry gene: trigger if RSI oversold or SMA trending up
        if (primary.indicatorType() == Gene.IndicatorType.RSI) {
            return value < RSI_OVERSOLD;
        }
        // Generic threshold: indicator above its own value from last bar is bullish
        return value > getPreviousIndicatorValue(primary);
    }

    /**
     * Evaluates whether the exit conditions encoded in the chromosome are met.
     */
    private boolean evaluateExitConditions(Bar bar) {
        List<Gene> exits = chromosome.exitGenes();
        if (exits.isEmpty()) return false;

        Gene primary = exits.get(0);
        double value = getIndicatorValue(primary, exitSmaCache, exitRsiCache, exitAtrCache);

        if (exits.size() >= 2) {
            Gene secondary = exits.get(1);
            double secondaryValue = getIndicatorValue(secondary, exitSmaCache, exitRsiCache, exitAtrCache);
            return value < secondaryValue;
        }

        // Single exit gene: trigger if RSI overbought or SMA trending down
        if (primary.indicatorType() == Gene.IndicatorType.RSI) {
            return value > RSI_OVERBOUGHT;
        }
        return value < getPreviousIndicatorValue(primary);
    }

    /**
     * Returns the current value of an indicator for a given gene.
     */
    private double getIndicatorValue(Gene gene,
                                      Map<String, SimpleMovingAverage> smaCache,
                                      Map<String, RsiValue> rsiCache,
                                      Map<String, AtrValue> atrCache) {
        String key = geneKey(gene);
        return switch (gene.indicatorType()) {
            case SMA, EMA -> {
                SimpleMovingAverage sma = smaCache.get(key);
                yield sma != null ? sma.value() : 0.0;
            }
            case RSI -> {
                RsiValue rsi = rsiCache.get(key);
                yield rsi != null ? rsi.value() : 50.0;
            }
            case ATR -> {
                AtrValue atr = atrCache.get(key);
                yield atr != null ? atr.value() : 0.0;
            }
            case ADX -> {
                RsiValue rsi = rsiCache.get(key + "_adx");
                yield rsi != null ? rsi.value() : 25.0;
            }
        };
    }

    /**
     * Returns the previous (second-to-last) value of an indicator.
     */
    private double getPreviousIndicatorValue(Gene gene) {
        String key = geneKey(gene) + "_prev";
        switch (gene.indicatorType()) {
            case SMA, EMA -> {
                SimpleMovingAverage sma = entrySmaCache.getOrDefault(key.replace("_prev", ""),
                    exitSmaCache.getOrDefault(key.replace("_prev", ""), null));
                return sma != null ? sma.previousValue() : 0.0;
            }
            case RSI -> {
                RsiValue rsi = entryRsiCache.getOrDefault(key.replace("_prev", ""),
                    exitRsiCache.getOrDefault(key.replace("_prev", ""), null));
                return rsi != null ? rsi.previousValue() : 50.0;
            }
            default -> {
                return 0.0;
            }
        }
    }

    /**
     * Gets the price field value from a bar.
     */
    private static double getFieldValue(Bar bar, Gene.Field field) {
        return switch (field) {
            case CLOSE -> bar.close();
            case OPEN -> bar.open();
            case HIGH -> bar.high();
            case LOW -> bar.low();
        };
    }

    /**
     * Creates a cache key for a gene.
     */
    private static String geneKey(Gene gene) {
        return gene.indicatorType() + "_" + gene.period() + "_" + gene.field();
    }

    // ---------------------------------------------------------------
    //  Inner classes for indicator value tracking
    // ---------------------------------------------------------------

    /**
     * Tracks a simple (or exponential) moving average over a window of values.
     */
    static final class SimpleMovingAverage {
        private final double[] values;
        private final int period;
        private int index;
        private int count;
        private double sum;
        private double previousValue;

        SimpleMovingAverage(int period) {
            this.period = period;
            this.values = new double[period];
            this.index = 0;
            this.count = 0;
            this.sum = 0.0;
            this.previousValue = 0.0;
        }

        void add(double value) {
            if (count < period) {
                sum += value;
                values[index] = value;
                index = (index + 1) % period;
                count++;
            } else {
                previousValue = sum / period;
                double oldest = values[index];
                sum = sum - oldest + value;
                values[index] = value;
                index = (index + 1) % period;
            }
        }

        double value() {
            if (count == 0) return 0.0;
            return sum / Math.min(count, period);
        }

        double previousValue() {
            return previousValue;
        }
    }

    /**
     * Tracks RSI (Relative Strength Index) over a window.
     */
    static final class RsiValue {
        private final int period;
        private final double[] gains;
        private final double[] losses;
        private int index;
        private int count;
        private double previousClose;
        private boolean hasPrevious;
        private double prevValue;

        RsiValue(int period) {
            this.period = period;
            this.gains = new double[period];
            this.losses = new double[period];
            this.index = 0;
            this.count = 0;
            this.hasPrevious = false;
            this.prevValue = 50.0;
        }

        void add(double price) {
            if (!hasPrevious) {
                hasPrevious = true;
                previousClose = price;
                return;
            }
            double change = price - previousClose;
            previousClose = price;

            if (count < period) {
                gains[index] = Math.max(change, 0);
                losses[index] = Math.max(-change, 0);
                index = (index + 1) % period;
                count++;
            } else {
                prevValue = value();
                gains[index] = Math.max(change, 0);
                losses[index] = Math.max(-change, 0);
                index = (index + 1) % period;
            }
        }

        double value() {
            if (count < 1) return 50.0;
            double avgGain = 0, avgLoss = 0;
            int n = Math.min(count, period);
            for (int i = 0; i < n; i++) {
                avgGain += gains[i];
                avgLoss += losses[i];
            }
            avgGain /= n;
            avgLoss /= n;
            if (avgLoss == 0) return 100.0;
            double rs = avgGain / avgLoss;
            return 100.0 - (100.0 / (1.0 + rs));
        }

        double previousValue() {
            return prevValue;
        }
    }

    /**
     * Tracks ATR (Average True Range) over a window.
     */
    static final class AtrValue {
        private final int period;
        private final double[] trueRanges;
        private int index;
        private int count;
        private double previousClose;
        private boolean hasPrevious;
        private double sum;

        AtrValue(int period) {
            this.period = period;
            this.trueRanges = new double[period];
            this.index = 0;
            this.count = 0;
            this.hasPrevious = false;
            this.sum = 0.0;
        }

        void add(Bar bar) {
            double tr;
            if (!hasPrevious) {
                tr = bar.high() - bar.low();
                hasPrevious = true;
            } else {
                tr = Math.max(bar.high() - bar.low(),
                    Math.max(Math.abs(bar.high() - previousClose),
                        Math.abs(bar.low() - previousClose)));
            }
            previousClose = bar.close();

            if (count < period) {
                sum += tr;
                trueRanges[index] = tr;
                index = (index + 1) % period;
                count++;
            } else {
                sum = sum - trueRanges[index] + tr;
                trueRanges[index] = tr;
                index = (index + 1) % period;
            }
        }

        double value() {
            if (count == 0) return 0.0;
            return sum / Math.min(count, period);
        }
    }
}
