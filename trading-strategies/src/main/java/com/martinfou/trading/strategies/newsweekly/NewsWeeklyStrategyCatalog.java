package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Registry of news/sentiment weekly strategies. Each is valid for exactly one week. */
public final class NewsWeeklyStrategyCatalog {

    private static final Map<String, Function<String, Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        NewsWeeklyStrategyCatalogRegistrar.registerAll();
    }

    private NewsWeeklyStrategyCatalog() {}

    public static void register(String key, Function<String, Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key, String symbol) {
        Function<String, Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown news weekly strategy: " + key);
        }
        return factory.apply(symbol);
    }

    public static Map<String, Function<String, Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    public static String defaultSymbol(String key) {
        if (key.contains("EUR_USD")) return "EUR_USD";
        if (key.contains("AUD_USD")) return "AUD_USD";
        if (key.contains("NZD_USD")) return "NZD_USD";
        if (key.contains("USD_JPY")) return "USD_JPY";
        if (key.contains("GBP_USD")) return "GBP_USD";
        return "EUR_USD";
    }
}
