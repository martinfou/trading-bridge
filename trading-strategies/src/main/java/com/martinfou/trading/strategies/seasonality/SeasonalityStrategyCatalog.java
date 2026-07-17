package com.martinfou.trading.strategies.seasonality;

import com.martinfou.trading.core.Strategy;
import java.util.*;

/** Registry of seasonality-only strategies. Each trades one calendar window per year. */
public final class SeasonalityStrategyCatalog {

    private static final Map<String, Strategy> STRATEGIES = new LinkedHashMap<>();

    static {
        register("UsdCadAutumnBullish", new UsdCadAutumnBullish());
        register("GbpUsdSpringBullish", new GbpUsdSpringBullish());
        // More to come after backtesting
    }

    private SeasonalityStrategyCatalog() {}

    public static void register(String id, Strategy strategy) {
        STRATEGIES.put(id, strategy);
    }

    public static Strategy get(String id) {
        return STRATEGIES.get(id);
    }

    public static Map<String, Strategy> all() {
        return Map.copyOf(STRATEGIES);
    }
}
