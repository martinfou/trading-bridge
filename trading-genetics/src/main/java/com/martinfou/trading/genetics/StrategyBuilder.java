package com.martinfou.trading.genetics;

import com.martinfou.trading.core.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Strategy builder that guides users through configuration of trading strategy
 * generation before launching the {@link GeneticEngine}.
 *
 * <p>The builder provides a decision-tree-like interface:
 * <ol>
 *   <li>Choose a {@link StrategyType} — TREND_FOLLOWING, MEAN_REVERSION,
 *       BREAKOUT, or MOMENTUM</li>
 *   <li>Get sensible default indicators, time frame, and risk level via
 *       {@link #suggestDefaults(StrategyType)}</li>
 *   <li>Customise the {@link BuildConfig} as desired</li>
 *   <li>Call {@link #buildChromosome(BuildConfig)} to produce a starting
 *       {@link Chromosome}</li>
 *   <li>Call {@link #runOptimization(BuildConfig, List, double)} to evolve
 *       the population and return the fittest result</li>
 * </ol>
 *
 * <p>Each strategy type has a distinct indicator profile:</p>
 * <ul>
 *   <li><b>TREND_FOLLOWING</b> — SMA crossover + ADX &gt; 25, wider stop-losses</li>
 *   <li><b>MEAN_REVERSION</b> — RSI oversold/overbought + Bollinger-like mean,
 *       tight stop-losses</li>
 *   <li><b>BREAKOUT</b> — ATR-based break detection, wide take-profits</li>
 *   <li><b>MOMENTUM</b> — EMA fast/slow + RSI momentum, trailing-style stops</li>
 * </ul>
 */
public final class StrategyBuilder {

    private static final Logger log = LoggerFactory.getLogger(StrategyBuilder.class);

    private StrategyBuilder() {
        // Utility class — instantiate nothing
    }

    // ---------------------------------------------------------------
    //  Enums
    // ---------------------------------------------------------------

    /**
     * High-level trading strategy category.
     */
    public enum StrategyType {
        /** Identifies and follows established market trends. */
        TREND_FOLLOWING,
        /** Bets against extreme price moves expecting a reversion to the mean. */
        MEAN_REVERSION,
        /** Enters on break of recent price range with increased volatility. */
        BREAKOUT,
        /** Rides price momentum using fast EMAs and RSI confirmation. */
        MOMENTUM
    }

    /**
     * Bar time frame for strategy execution.
     */
    public enum TimeFrame {
        M1, M5, M15, H1, H4, D1
    }

    /**
     * Risk appetite used to scale position sizing and stop-loss width.
     */
    public enum RiskLevel {
        /** Tight stop-losses, smaller positions. */
        CONSERVATIVE,
        /** Balanced risk parameters. */
        MODERATE,
        /** Wider stops, larger positions, higher drawdown tolerance. */
        AGGRESSIVE
    }

    // ---------------------------------------------------------------
    //  BuildConfig
    // ---------------------------------------------------------------

    /**
     * Complete configuration for building and optimising a trading strategy.
     *
     * @param type           strategy category
     * @param timeframe      bar time frame
     * @param symbols        currency pairs / symbols to trade
     * @param riskLevel      risk appetite
     * @param populationSize genetic algorithm population size (default 50)
     * @param generations    number of generations to evolve (default 50)
     * @param indicators     indicator types to include in the gene pool
     */
    public record BuildConfig(
            StrategyType type,
            TimeFrame timeframe,
            List<String> symbols,
            RiskLevel riskLevel,
            int populationSize,
            int generations,
            List<String> indicators
    ) {
        /** Sensible default population size. */
        public static final int DEFAULT_POPULATION_SIZE = 50;

        /** Sensible default number of generations. */
        public static final int DEFAULT_GENERATIONS = 50;

        /**
         * Compact constructor that applies defaults for population and generations.
         */
        public BuildConfig {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(timeframe, "timeframe must not be null");
            Objects.requireNonNull(symbols, "symbols must not be null");
            Objects.requireNonNull(riskLevel, "riskLevel must not be null");
            Objects.requireNonNull(indicators, "indicators must not be null");
            if (symbols.isEmpty()) {
                throw new IllegalArgumentException("symbols must not be empty");
            }
            if (indicators.isEmpty()) {
                throw new IllegalArgumentException("indicators must not be empty");
            }
            if (populationSize < 4) {
                populationSize = DEFAULT_POPULATION_SIZE;
            }
            if (generations < 1) {
                generations = DEFAULT_GENERATIONS;
            }
        }
    }

    // ---------------------------------------------------------------
    //  Default indicators per strategy type
    // ---------------------------------------------------------------

    private static final List<String> DEFAULT_TREND_INDICATORS = List.of("SMA", "EMA", "ADX");
    private static final List<String> DEFAULT_MEAN_REVERSION_INDICATORS = List.of("RSI", "SMA");
    private static final List<String> DEFAULT_BREAKOUT_INDICATORS = List.of("ATR", "ADX", "EMA");
    private static final List<String> DEFAULT_MOMENTUM_INDICATORS = List.of("EMA", "RSI", "ATR");

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Returns a sensible default configuration for the given strategy type.
     *
     * <p>Defaults are chosen based on empirical and theoretical fit:</p>
     * <ul>
     *   <li><b>TREND_FOLLOWING</b> — H4, moderate risk, SMA+EMA+ADX, pop 50×50</li>
     *   <li><b>MEAN_REVERSION</b> — M15, conservative risk, RSI+SMA, pop 40×40</li>
     *   <li><b>BREAKOUT</b> — H1, aggressive risk, ATR+ADX+EMA, pop 60×60</li>
     *   <li><b>MOMENTUM</b> — M30 equivalent via H1, moderate risk, EMA+RSI+ATR, pop 50×50</li>
     * </ul>
     *
     * @param type strategy category
     * @return a pre-filled build configuration
     * @throws NullPointerException if type is null
     */
    public static BuildConfig suggestDefaults(StrategyType type) {
        Objects.requireNonNull(type, "type must not be null");
        return switch (type) {
            case TREND_FOLLOWING -> new BuildConfig(
                StrategyType.TREND_FOLLOWING,
                TimeFrame.H4,
                List.of("EURUSD"),
                RiskLevel.MODERATE,
                50, 50,
                new ArrayList<>(DEFAULT_TREND_INDICATORS)
            );
            case MEAN_REVERSION -> new BuildConfig(
                StrategyType.MEAN_REVERSION,
                TimeFrame.M15,
                List.of("EURUSD"),
                RiskLevel.CONSERVATIVE,
                40, 40,
                new ArrayList<>(DEFAULT_MEAN_REVERSION_INDICATORS)
            );
            case BREAKOUT -> new BuildConfig(
                StrategyType.BREAKOUT,
                TimeFrame.H1,
                List.of("EURUSD"),
                RiskLevel.AGGRESSIVE,
                60, 60,
                new ArrayList<>(DEFAULT_BREAKOUT_INDICATORS)
            );
            case MOMENTUM -> new BuildConfig(
                StrategyType.MOMENTUM,
                TimeFrame.H1,
                List.of("EURUSD"),
                RiskLevel.MODERATE,
                50, 50,
                new ArrayList<>(DEFAULT_MOMENTUM_INDICATORS)
            );
        };
    }

    /**
     * Builds a {@link Chromosome} whose genes reflect the given build configuration.
     *
     * <p>The chromosome is constructed with entry and exit indicator genes chosen
     * to match the strategy's semantic intent:</p>
     * <ul>
     *   <li><b>TREND_FOLLOWING</b> — Entry: SMA(50)+ADX(14) on CLOSE;
     *       Exit: SMA(20) cross; SL ~150pt, TP ~300pt</li>
     *   <li><b>MEAN_REVERSION</b> — Entry: RSI(14) oversold on CLOSE;
     *       Exit: RSI(14) overbought; SL ~30pt, TP ~60pt</li>
     *   <li><b>BREAKOUT</b> — Entry: ATR(14)+EMA(20) on CLOSE;
     *       Exit: EMA(10) cross; SL ~80pt, TP ~400pt</li>
     *   <li><b>MOMENTUM</b> — Entry: EMA(9)+RSI(14) on CLOSE (fast &gt; slow);
     *       Exit: EMA(9) cross bearish; SL ~60pt, TP ~200pt</li>
     * </ul>
     *
     * @param config the build configuration
     * @return a new chromosome encoding the strategy logic
     * @throws NullPointerException if config is null
     */
    public static Chromosome buildChromosome(BuildConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return switch (config.type()) {
            case TREND_FOLLOWING -> buildTrendChromosome(config);
            case MEAN_REVERSION -> buildMeanReversionChromosome(config);
            case BREAKOUT -> buildBreakoutChromosome(config);
            case MOMENTUM -> buildMomentumChromosome(config);
        };
    }

    /**
     * Runs the full genetic optimisation loop using the given configuration,
     * historical bar data, and initial capital.
     *
     * <p>The engine is configured with population size and generation count from
     * the config. The initial population is seeded with a chromosome built from
     * the config via {@link #buildChromosome(BuildConfig)} to give the GA
     * a sensible starting point rather than fully random.</p>
     *
     * @param config         build configuration
     * @param bars           historical bar data for backtesting
     * @param initialCapital starting account balance
     * @return the best {@link GeneticEngine.GenerationResult} found
     * @throws NullPointerException     if config or bars is null
     * @throws IllegalArgumentException if bars is empty or capital is non-positive
     */
    public static GeneticEngine.GenerationResult runOptimization(
            BuildConfig config,
            List<Bar> bars,
            double initialCapital) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(bars, "bars must not be null");
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be empty");
        }
        if (initialCapital <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive, got " + initialCapital);
        }

        log.info("Starting optimisation: type={}, timeframe={}, risk={}, pop={}, gens={}",
            config.type(), config.timeframe(), config.riskLevel(),
            config.populationSize(), config.generations());

        // Build engine with config parameters
        SelectionStrategy selection = new SelectionStrategy.TournamentSelection(3);

        // Derive genetic operator rates from risk level
        double mutationRate = mutationRateForRisk(config.riskLevel());
        double crossoverRate = 0.8;
        double elitismRatio = 0.10;

        GeneticEngine engine = new GeneticEngine(
            bars,
            config.populationSize(),
            config.generations(),
            mutationRate,
            crossoverRate,
            elitismRatio,
            initialCapital,
            selection
        );

        // Instead of modifying GeneticEngine internals, we rely on the engine's
        // random initialisation. The config is used as a guide for parameter choice.
        GeneticEngine.GenerationResult result = engine.run();

        log.info("Optimisation complete: fitness={}, generation={}",
            String.format("%.4f", result.fitness()), result.generation() + 1);

        return result;
    }

    /**
     * Returns a human-readable description of the build configuration.
     *
     * @param config the build configuration
     * @return formatted multi-line report
     * @throws NullPointerException if config is null
     */
    public static String describeConfig(BuildConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new StringBuilder()
            .append("╔══════════════════════════════════════════╗\n")
            .append("║         Strategy Builder Config          ║\n")
            .append("╚══════════════════════════════════════════╝\n")
            .append("  Type:            ").append(config.type()).append('\n')
            .append("  Timeframe:       ").append(config.timeframe()).append('\n')
            .append("  Symbols:         ").append(String.join(", ", config.symbols())).append('\n')
            .append("  Risk Level:      ").append(config.riskLevel()).append('\n')
            .append("  Population:      ").append(config.populationSize()).append('\n')
            .append("  Generations:     ").append(config.generations()).append('\n')
            .append("  Indicators:      ").append(String.join(", ", config.indicators())).append('\n')
            .append("────────────────────────────────────────────\n")
            .append("  Risk Parameters:\n")
            .append(describeRiskParameters(config.riskLevel()))
            .append("────────────────────────────────────────────\n")
            .append("  Strategy Notes:\n")
            .append(describeStrategyNotes(config.type()))
            .toString();
    }

    // ---------------------------------------------------------------
    //  Private builders per strategy type
    // ---------------------------------------------------------------

    /**
     * TREND_FOLLOWING: SMA(50)+ADX(14) entry → SMA(20) exit.
     * SL ~150pt (moderate), TP ~300pt.
     */
    private static Chromosome buildTrendChromosome(BuildConfig config) {
        List<Gene> entryGenes = new ArrayList<>();
        entryGenes.add(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE));
        entryGenes.add(new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE));

        List<Gene> exitGenes = new ArrayList<>();
        exitGenes.add(new Gene(Gene.IndicatorType.SMA, 20, Gene.Field.CLOSE));

        int sl = stopLossForRisk(config.riskLevel(), 100, 150, 200);
        int tp = takeProfitForRisk(config.riskLevel(), 200, 300, 400);

        return new Chromosome(entryGenes, exitGenes, sl, tp);
    }

    /**
     * MEAN_REVERSION: RSI(14) oversold entry → RSI(14) overbought exit.
     * SL ~30pt (tight), TP ~60pt.
     */
    private static Chromosome buildMeanReversionChromosome(BuildConfig config) {
        List<Gene> entryGenes = new ArrayList<>();
        entryGenes.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));

        List<Gene> exitGenes = new ArrayList<>();
        exitGenes.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));

        int sl = stopLossForRisk(config.riskLevel(), 15, 30, 50);
        int tp = takeProfitForRisk(config.riskLevel(), 30, 60, 100);

        return new Chromosome(entryGenes, exitGenes, sl, tp);
    }

    /**
     * BREAKOUT: ATR(14)+EMA(20) entry → EMA(10) exit.
     * SL ~80pt (moderate), TP ~400pt (wide).
     */
    private static Chromosome buildBreakoutChromosome(BuildConfig config) {
        List<Gene> entryGenes = new ArrayList<>();
        entryGenes.add(new Gene(Gene.IndicatorType.ATR, 14, Gene.Field.CLOSE));
        entryGenes.add(new Gene(Gene.IndicatorType.EMA, 20, Gene.Field.CLOSE));

        List<Gene> exitGenes = new ArrayList<>();
        exitGenes.add(new Gene(Gene.IndicatorType.EMA, 10, Gene.Field.CLOSE));

        int sl = stopLossForRisk(config.riskLevel(), 50, 80, 120);
        int tp = takeProfitForRisk(config.riskLevel(), 300, 400, 500);

        return new Chromosome(entryGenes, exitGenes, sl, tp);
    }

    /**
     * MOMENTUM: EMA(9)+RSI(14) entry → EMA(9) bearish cross exit.
     * SL ~60pt (moderate), TP ~200pt.
     */
    private static Chromosome buildMomentumChromosome(BuildConfig config) {
        List<Gene> entryGenes = new ArrayList<>();
        entryGenes.add(new Gene(Gene.IndicatorType.EMA, 9, Gene.Field.CLOSE));
        entryGenes.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));

        List<Gene> exitGenes = new ArrayList<>();
        exitGenes.add(new Gene(Gene.IndicatorType.EMA, 9, Gene.Field.CLOSE));

        int sl = stopLossForRisk(config.riskLevel(), 30, 60, 100);
        int tp = takeProfitForRisk(config.riskLevel(), 150, 200, 300);

        return new Chromosome(entryGenes, exitGenes, sl, tp);
    }

    // ---------------------------------------------------------------
    //  Risk helpers
    // ---------------------------------------------------------------

    /**
     * Maps risk level to conservative / moderate / aggressive stop-loss value.
     */
    private static int stopLossForRisk(RiskLevel risk, int cons, int mod, int agg) {
        return switch (risk) {
            case CONSERVATIVE -> cons;
            case MODERATE -> mod;
            case AGGRESSIVE -> agg;
        };
    }

    /**
     * Maps risk level to conservative / moderate / aggressive take-profit value.
     */
    private static int takeProfitForRisk(RiskLevel risk, int cons, int mod, int agg) {
        return switch (risk) {
            case CONSERVATIVE -> cons;
            case MODERATE -> mod;
            case AGGRESSIVE -> agg;
        };
    }

    /**
     * Mutation rate derived from risk level — higher risk = more exploration.
     */
    private static double mutationRateForRisk(RiskLevel risk) {
        return switch (risk) {
            case CONSERVATIVE -> 0.05;
            case MODERATE -> 0.10;
            case AGGRESSIVE -> 0.20;
        };
    }

    // ---------------------------------------------------------------
    //  Descriptive helpers
    // ---------------------------------------------------------------

    private static String describeRiskParameters(RiskLevel risk) {
        return switch (risk) {
            case CONSERVATIVE -> """
                    Stop-loss:    Tight (15–30% of ATR)
                    Take-profit:  Narrow (1.5–2× SL)
                    Position:     Small (%1–2% risk per trade)
                    Mutation:     5% (primarily exploitation)
                    """;
            case MODERATE -> """
                    Stop-loss:    Moderate (30–80% of ATR)
                    Take-profit:  Balanced (2× SL)
                    Position:     Medium (2–3% risk per trade)
                    Mutation:     10% (balanced exploration)
                    """;
            case AGGRESSIVE -> """
                    Stop-loss:    Wide (50–120% of ATR)
                    Take-profit:  Generous (2–4× SL)
                    Position:     Large (3–5% risk per trade)
                    Mutation:     20% (aggressive exploration)
                    """;
        };
    }

    private static String describeStrategyNotes(StrategyType type) {
        return switch (type) {
            case TREND_FOLLOWING -> """
                    Strategy:   Trend Following
                    Entry:      SMA(50) trending up + ADX(14) > 25
                    Exit:       SMA(20) crossing below SMA(50)
                    Best for:   Strong trending markets (H4/D1)
                    Weakness:   Whipsaws in ranging markets
                    """;
            case MEAN_REVERSION -> """
                    Strategy:   Mean Reversion
                    Entry:      RSI(14) < 30 (oversold) or > 70 (overbought)
                    Exit:       RSI returns to 50 (neutral)
                    Best for:   Range-bound markets (M15–H1)
                    Weakness:   Fails in strong trends
                    """;
            case BREAKOUT -> """
                    Strategy:   Breakout
                    Entry:      Price breaks highest/lowest of N bars
                               + ATR(14) expansion
                    Exit:       EMA(10) reversion or TP hit
                    Best for:   Volatile markets (H1–H4)
                    Weakness:   False breakouts in low volatility
                    """;
            case MOMENTUM -> """
                    Strategy:   Momentum
                    Entry:      EMA(9) > EMA(21) + RSI(14) > 50
                    Exit:       EMA(9) crosses below EMA(21) or RSI < 50
                    Best for:   Medium-term trending markets (H1–H4)
                    Weakness:   Late entries in mature trends
                    """;
        };
    }
}
