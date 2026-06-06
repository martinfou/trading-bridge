package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Registry of deterministic backtest probe strategies ({@code Harness_*} ids). */
public final class HarnessStrategyCatalog {

    private static final Map<String, Function<String, Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        register("Harness_NeverTrade", NeverTradeStrategy::new);
        register("Harness_BuyOnceHold", BuyOnceHoldStrategy::new);
        register("Harness_BuyThenCloseNextBar", BuyThenCloseNextBarStrategy::new);
        register("Harness_LimitNeverFills", LimitNeverFillsStrategy::new);
        register("Harness_OpenCloseSameBar", OpenCloseSameBarStrategy::new);
        register("Harness_WeekendProbe", WeekendProbeStrategy::new);
        register("Harness_WeekendOnlyTrade", WeekendOnlyTradeStrategy::new);
        register("Harness_DailyOpenClose", DailyOpenCloseStrategy::new);
        register("Harness_DailyRoundTrip", DailyRoundTripStrategy::new);
        register("Harness_WeeklyRoundTrip", WeeklyRoundTripStrategy::new);
    }

    private HarnessStrategyCatalog() {}

    public static void register(String key, Function<String, Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key, String symbol) {
        Function<String, Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown harness strategy: " + key);
        }
        return factory.apply(symbol);
    }

    public static Map<String, Function<String, Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    public static Set<String> ids() {
        return Set.copyOf(FACTORIES.keySet());
    }

    public static String defaultSymbol(String key) {
        return "EUR_USD";
    }
}
