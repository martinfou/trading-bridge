package com.martinfou.trading.strategies.harness;

/**
 * Attempts a same-bar round-trip on every bar the engine delivers.
 * <p>Use with a Mon–Sun series that includes Saturday/Sunday bars in the CSV:
 * expect {@code totalTrades == weekdayBarCount} (5 for one daily bar per day)
 * because spot FX weekend bars must not invoke {@code onBar}.</p>
 *
 * <pre>
 * mvn exec:java -pl trading-examples \
 *   -Dexec.mainClass=com.martinfou.trading.examples.RunBacktest \
 *   -Dexec.args="Harness_WeekendProbe EUR_USD 2024"
 * </pre>
 */
public final class WeekendProbeStrategy extends EveryBarRoundTripHarness {

    public WeekendProbeStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_WeekendProbe";
    }
}
