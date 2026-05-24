package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Registry of StrategyQuant-imported strategies (no-arg constructors). */
public final class SqImportedStrategyCatalog {

    private static final Map<String, Supplier<Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        register("Strategy_2_14_147_Adapted", Strategy_2_14_147_Adapted::new);
        register("Strategy_2_15_195_Adapted", Strategy_2_15_195_Adapted::new);
        register("Strategy_2_31_175_Converted", Strategy_2_31_175_Converted::new);
        register("Strategy_2_31_177_Converted", Strategy_2_31_177_Converted::new);
        register("Strategy_2_32_120_Converted", Strategy_2_32_120_Converted::new);
        register("Strategy_2_36_190_Converted", Strategy_2_36_190_Converted::new);
        register("Strategy_2_38_112_Converted", Strategy_2_38_112_Converted::new);
    }

    private SqImportedStrategyCatalog() {}

    public static void register(String key, Supplier<Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key) {
        Supplier<Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown sqimported strategy: " + key);
        }
        return factory.get();
    }

    public static Map<String, Supplier<Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    public static String defaultSymbol(String key) {
        if (!FACTORIES.containsKey(key)) {
            throw new IllegalArgumentException("Unknown sqimported strategy: " + key);
        }
        return "GBP_JPY";
    }
}
