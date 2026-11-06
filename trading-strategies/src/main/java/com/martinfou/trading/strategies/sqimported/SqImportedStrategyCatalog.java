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
        register("SqMgcM30Breakout034297", SqMgcM30Breakout034297::new);
        register("SqMgcM30Breakout011248", SqMgcM30Breakout011248::new);
        register("SqMnqM15Momentum06737", SqMnqM15Momentum06737::new);
        register("MesH1MeanRev29315", MesH1MeanRev29315::new);
        register("MesH1MeanRev49622", MesH1MeanRev49622::new);
        register("MgcM30MeanRev50426", MgcM30MeanRev50426::new);
        register("MgcM30Pullback51084", MgcM30Pullback51084::new);
        register("MgcM30Trend28281", MgcM30Trend28281::new);
        register("MnqH1Momentum66294", MnqH1Momentum66294::new);
        register("MnqH1MeanRev91321", MnqH1MeanRev91321::new);
        register("MgcM30Momentum86612", MgcM30Momentum86612::new);
        register("AaplM30Momentum82914", AaplM30Momentum82914::new);
        register("MgcH1Trend29170", MgcH1Trend29170::new);
        register("MgcH1Trend64270", MgcH1Trend64270::new);
        // Legacy Sq-prefixed aliases for backward compatibility
        register("SqMesH1MeanRev29315", MesH1MeanRev29315::new);
        register("SqMesH1MeanRev49622", MesH1MeanRev49622::new);
        register("SqMgcM30MeanRev50426", MgcM30MeanRev50426::new);
        register("SqMgcM30Pullback51084", MgcM30Pullback51084::new);
        register("SqMgcM30Trend28281", MgcM30Trend28281::new);
        register("SqMnqH1Momentum66294", MnqH1Momentum66294::new);
        register("SqMnqH1MeanRev91321", MnqH1MeanRev91321::new);
        register("SqMgcM30Momentum86612", MgcM30Momentum86612::new);
        register("SqAaplM30Momentum82914", AaplM30Momentum82914::new);
        register("SqMgcH1Trend29170", MgcH1Trend29170::new);
        register("SqMgcH1Trend64270", MgcH1Trend64270::new);
        register("MgcM30Pullback22384", MgcM30Pullback22384::new);
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
        if (upper.contains("XAUUSD")) return "XAU_USD";
        if (upper.contains("GOLD") || upper.contains("MGC")) return "MGC";
        if (upper.startsWith("GC") || upper.startsWith("SQGC")) return "GC";
        if (upper.contains("MNQ")) return "MNQ";
        if (upper.startsWith("NQ") || upper.startsWith("SQNQ")) return "NQ";
        if (upper.contains("MES")) return "MES";
        if (upper.startsWith("ES") || upper.startsWith("SQES")) return "ES";
        if (upper.contains("M2K")) return "M2K";
        if (upper.contains("EMD")) return "EMD";
        if (upper.contains("AAPL")) return "AAPL";
        if (upper.contains("NVDA")) return "NVDA";
        if (upper.contains("TSLA")) return "TSLA";
        if (upper.contains("MSFT")) return "MSFT";
        if (upper.contains("SPY")) return "SPY";
        if (upper.contains("QQQ")) return "QQQ";
        if (upper.contains("EURUSD")) return "EUR_USD";
        if (upper.contains("GBPUSD")) return "GBP_USD";
        if (upper.contains("USDJPY")) return "USD_JPY";
        if (upper.contains("AUDUSD")) return "AUD_USD";
        if (upper.contains("USDCAD")) return "USD_CAD";
        if (upper.contains("USDCHF")) return "USD_CHF";
        if (upper.contains("NZDUSD")) return "NZD_USD";
        return "GBP_JPY";
    }
}
