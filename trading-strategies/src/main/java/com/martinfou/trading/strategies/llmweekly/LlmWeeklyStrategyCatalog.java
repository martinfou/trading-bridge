package com.martinfou.trading.strategies.llmweekly;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Registry of LLM weekly generated strategies (Epic 22.4). */
public final class LlmWeeklyStrategyCatalog {

    private static final Map<String, Function<String, Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        LlmWeeklyStrategyCatalogRegistrar.registerAll();
    }

    private LlmWeeklyStrategyCatalog() {}

    public static void register(String key, Function<String, Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key, String symbol) {
        Function<String, Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown llm weekly strategy: " + key);
        }
        return factory.apply(symbol);
    }

    public static Map<String, Function<String, Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    public static String defaultSymbol(String key) {
        if (!FACTORIES.containsKey(key)) {
            throw new IllegalArgumentException("Unknown llm weekly strategy: " + key);
        }
        for (String templateId : java.util.List.of("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8")) {
            String marker = "_" + templateId + "_";
            int idx = key.indexOf(marker);
            if (idx >= 0) {
                String tail = key.substring(idx + marker.length());
                return "NONE".equals(tail) ? "EUR_USD" : tail;
            }
        }
        return "EUR_USD";
    }
}
