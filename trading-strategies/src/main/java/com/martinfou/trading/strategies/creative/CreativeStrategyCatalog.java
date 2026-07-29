package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of all creative strategies from market research, data mining, and
 * hypothesis-driven strategy design. Each was individually crafted from
 * pattern exploration — not batch-generated.
 *
 * Strategies whose constructors accept (String name, String symbol) use the
 * supplied symbol. Strategies with a hardcoded SYMBOL constant ignore the
 * symbol parameter and always trade their designated pair.
 */
public final class CreativeStrategyCatalog {

    private static final Map<String, Function<String, Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        CreativeStrategyCatalogRegistrar.registerAll();
    }

    private CreativeStrategyCatalog() {}

    public static void register(String key, Function<String, Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key, String symbol) {
        Function<String, Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown creative strategy: " + key);
        }
        return factory.apply(symbol);
    }

    public static Map<String, Function<String, Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    public static String defaultSymbol(String key) {
        return "EUR_USD";
    }
}
