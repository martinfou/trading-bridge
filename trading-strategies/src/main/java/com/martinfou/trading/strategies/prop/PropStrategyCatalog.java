package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.martinfou.trading.strategies.creative.BollingerBreakoutMomentum;
import com.martinfou.trading.strategies.creative.EmaCloudTrendFilter;
import com.martinfou.trading.strategies.creative.RSIPulseMomentumStrategy;
import com.martinfou.trading.strategies.creative.RSI3MeanReversion;
import com.martinfou.trading.strategies.creative.DlrMeanReversion;

/**
 * Registry of all prop-firm mechanical strategies. */
public final class PropStrategyCatalog {

    private static final Map<String, Function<String, Strategy>> FACTORIES = new LinkedHashMap<>();

    static {
        register("LondonOpenRangeBreakout", LondonOpenRangeBreakoutStrategy::new);
        register("AsianRangeMeanReversion", AsianRangeMeanReversionStrategy::new);
        register("SupplyDemandZone", SupplyDemandZoneStrategy::new);
        register("EmaPullbackContinuation", EmaPullbackContinuationStrategy::new);
        register("ConnorsRsi2", ConnorsRsi2Strategy::new);
        register("PdhlSweepReversal", PdhlSweepReversalStrategy::new);
        register("NyContinuation", NyContinuationStrategy::new);
        register("WeeklyOpenGapFade", WeeklyOpenGapFadeStrategy::new);
        register("InsideBarBreakout", InsideBarBreakoutStrategy::new);
        register("OverlapMomentumBurst", OverlapMomentumBurstStrategy::new);
        register("BollingerBreakoutMomentum", sym -> new BollingerBreakoutMomentum("BollingerBreakoutMomentum_" + sym, sym));
        register("EmaCloudTrendFilter", sym -> new EmaCloudTrendFilter("EmaCloudTrendFilter_" + sym, sym));
        register("RSIPulseMomentum", sym -> new RSIPulseMomentumStrategy("RSIPulseMomentum_" + sym, sym));
        register("RSI3MeanReversion", sym -> new RSI3MeanReversion("RSI3MeanReversion_" + sym, sym));
        register("DlrMeanReversion", sym -> new DlrMeanReversion("DlrMeanReversion_" + sym, sym));
    }

    private PropStrategyCatalog() {}

    public static void register(String key, Function<String, Strategy> factory) {
        FACTORIES.put(key, factory);
    }

    public static Strategy create(String key, String symbol) {
        Function<String, Strategy> factory = FACTORIES.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown prop strategy: " + key);
        }
        return factory.apply(symbol);
    }

    public static Map<String, Function<String, Strategy>> all() {
        return Map.copyOf(FACTORIES);
    }

    /** Default symbol per strategy (high-liquidity pair from spec). */
    public static String defaultSymbol(String key) {
        return switch (key) {
            case "AsianRangeMeanReversion" -> "USD_JPY";
            case "WeeklyOpenGapFade" -> "USD_JPY";
            case "NyContinuation", "OverlapMomentumBurst" -> "GBP_USD";
            default -> "EUR_USD";
        };
    }
}
