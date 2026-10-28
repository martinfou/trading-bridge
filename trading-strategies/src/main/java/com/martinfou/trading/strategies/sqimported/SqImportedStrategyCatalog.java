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
        register("Strategy_Gold_Breakout_M30", Strategy_Gold_Breakout_M30::new);
        register("SqMnqM15Momentum04248", SqMnqM15Momentum04248::new);
        register("SqMnqM15Breakout01499", SqMnqM15Breakout01499::new);
        register("SqMesH1MeanRev03553", SqMesH1MeanRev03553::new);
        register("SqMesH1Pullback02700", SqMesH1Pullback02700::new);
        register("SqGbpJpyH1Breakout339125", SqGbpJpyH1Breakout339125::new);
        register("SqGbpJpyH1Breakout220102", SqGbpJpyH1Breakout220102::new);
        register("SqGbpJpyH1Breakout45173", SqGbpJpyH1Breakout45173::new);
        register("SqGbpJpyH1Breakout419179", SqGbpJpyH1Breakout419179::new);
        register("SqMgcM30Breakout129102", SqMgcM30Breakout129102::new);
        register("SqMgcM30Breakout024542", SqMgcM30Breakout024542::new);
        register("SqMgcM30Breakout032921", SqMgcM30Breakout032921::new);
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
        String upper = key.toUpperCase();
        if (upper.contains("GOLD") || upper.contains("MGC")) {
            return "MGC";
        }
        if (upper.contains("MNQ")) {
            return "MNQ";
        }
        if (upper.contains("MES")) {
            return "MES";
        }
        return "GBP_JPY";
    }
}
