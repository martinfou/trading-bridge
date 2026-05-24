package com.martinfou.trading.strategies.generated;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Registry of genetic-algorithm generated strategies. */
public final class GeneratedStrategyCatalog {

    private static final Map<String, Function<String, Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        register("MyStrategy", MyStrategy::new);
        register("Test123", Test123::new);
        register("TestFast", TestFast::new);
    }

    private GeneratedStrategyCatalog() {}

    public static void register(String key, Function<String, Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key, String symbol) {
        Function<String, Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown generated strategy: " + key);
        }
        return factory.apply(symbol);
    }

    public static Map<String, Function<String, Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    public static String defaultSymbol(String key) {
        if (!FACTORIES.containsKey(key)) {
            throw new IllegalArgumentException("Unknown generated strategy: " + key);
        }
        return "EUR_USD";
    }
}
