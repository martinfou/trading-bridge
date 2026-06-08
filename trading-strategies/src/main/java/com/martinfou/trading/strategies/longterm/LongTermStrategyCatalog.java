package com.martinfou.trading.strategies.longterm;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of all long-term strategies (15+ year horizons, weekly evaluation).
 * Each strategy is H1-bar based, risk-managed with ATR-based position sizing.
 */
public final class LongTermStrategyCatalog {

    private static final Map<String, Function<String, Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        register("LtCrossMomentum", sym -> new LtCrossMomentum("LtCrossMomentum", sym));
        register("LtRSIMeanRev", sym -> new LtRSIMeanRev("LtRSIMeanRev", sym));
        register("LtRSI3Momentum", sym -> new LtRSI3Momentum("LtRSI3Momentum", sym));
        register("LtRangeBreakout", sym -> new LtRangeBreakout("LtRangeBreakout", sym));
        register("LtVolRegime", sym -> new LtVolRegime("LtVolRegime", sym));
        register("LtBollingerSqueeze", sym -> new LtBollingerSqueeze("LtBollingerSqueeze", sym));
        register("LtSqueezeMomentum", sym -> new LtSqueezeMomentum("LtSqueezeMomentum", sym));
        register("LtPullbackEntry", sym -> new LtPullbackEntry("LtPullbackEntry", sym));
        register("LtDoubleMA", sym -> new LtDoubleMA("LtDoubleMA", sym));
        register("LtEfficiencyRatio", sym -> new LtEfficiencyRatio("LtEfficiencyRatio", sym));
    }

    private LongTermStrategyCatalog() {}

    public static void register(String key, Function<String, Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key, String symbol) {
        Function<String, Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown long-term strategy: " + key);
        }
        return factory.apply(symbol);
    }

    public static Map<String, Function<String, Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    /** Default symbol per strategy (all support multi-pair, EUR/USD as primary). */
    public static String defaultSymbol(String key) {
        return switch (key) {
            case "LtVolRegime", "LtBollingerSqueeze" -> "EUR_USD";
            case "LtRSI3Momentum", "LtDoubleMA" -> "EUR_USD";
            default -> "EUR_USD";
        };
    }
}
