package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.generated.GeneratedStrategyCatalog;
import com.martinfou.trading.strategies.harness.HarnessStrategyCatalog;
import com.martinfou.trading.strategies.llmweekly.LlmWeeklyStrategyCatalog;
import com.martinfou.trading.strategies.newsweekly.NewsWeeklyStrategyCatalog;
import com.martinfou.trading.strategies.prop.PropStrategyCatalog;
import com.martinfou.trading.strategies.sqimported.SqImportedStrategyCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Unified registry for all compiled strategy families.
 * Example strategies (e.g. SmaCrossover) register at runtime from {@code trading-examples}.
 */
public final class StrategyCatalog {

    public enum Family {
        PROP, SQ_IMPORTED, GENERATED, LLM_WEEKLY, NEWS_WEEKLY, HARNESS, LONG_TERM, EXAMPLE
    }

    public record Entry(
        String id,
        Family family,
        String defaultSymbol,
        String type,
        List<String> indicators,
        String description
    ) {}

    private record Registration(
        Family family,
        String defaultSymbol,
        String type,
        List<String> indicators,
        String description,
        Function<String, Strategy> factory
    ) {}

    private static final Map<String, Registration> ENTRIES = new LinkedHashMap<>();

    static {
        bootstrapBuiltIn();
    }

    private StrategyCatalog() {}

    private static String resolveType(String id, Family family) {
        if (family == Family.PROP) {
            return switch (id) {
                case "LondonOpenRangeBreakout", "OverlapMomentumBurst" -> "Session Breakout";
                case "AsianRangeMeanReversion", "ConnorsRsi2", "WeeklyOpenGapFade" -> "Mean Reversion";
                case "SupplyDemandZone", "PdhlSweepReversal" -> "Price Action";
                case "EmaPullbackContinuation", "NyContinuation" -> "Trend Following";
                case "InsideBarBreakout" -> "Momentum";
                default -> "Trend Following";
            };
        }
        if (family == Family.SQ_IMPORTED) {
            return "Rule-Based";
        }
        if (id.equals("SmaCrossover")) {
            return "Trend Following";
        }
        return "Trend Following";
    }

    private static List<String> resolveIndicators(String id, Family family) {
        if (family == Family.PROP) {
            return switch (id) {
                case "LondonOpenRangeBreakout" -> List.of("EMA", "ATR");
                case "AsianRangeMeanReversion" -> List.of("RSI", "ATR");
                case "SupplyDemandZone", "PdhlSweepReversal", "WeeklyOpenGapFade" -> List.of("ATR");
                case "EmaPullbackContinuation" -> List.of("EMA", "RSI", "ATR");
                case "ConnorsRsi2" -> List.of("RSI", "SMA");
                case "NyContinuation" -> List.of("EMA", "ATR");
                case "InsideBarBreakout" -> List.of("Bollinger Bands", "ATR");
                case "OverlapMomentumBurst" -> List.of("Consolidation Range");
                default -> List.of();
            };
        }
        if (family == Family.SQ_IMPORTED) {
            return List.of("EMA", "RSI", "Bollinger Bands");
        }
        if (id.equals("SmaCrossover")) {
            return List.of("SMA");
        }
        return List.of();
    }

    private static String resolveDescription(String id, Family family) {
        if (family == Family.PROP) {
            return switch (id) {
                case "LondonOpenRangeBreakout" -> "Enters on breakout of the 7:00–8:00 AM London range, filtered by H1 trend.";
                case "AsianRangeMeanReversion" -> "Fades Asian session highs/lows at London Open using candlestick reversals.";
                case "SupplyDemandZone" -> "Enters on price rejection of the base zone from the last impulsive H4-equivalent leg.";
                case "EmaPullbackContinuation" -> "Enters on pullbacks to the 20-EMA within a strong daily trend.";
                case "ConnorsRsi2" -> "Classic short-term mean reversion entry when RSI(2) is oversold/overbought.";
                case "PdhlSweepReversal" -> "Fades sweeps of the Previous Day High/Low (PDHL) liquidity levels.";
                case "NyContinuation" -> "Enters continuation trades during NY session after a strong London morning trend.";
                case "WeeklyOpenGapFade" -> "Fades the weekly market opening gap back toward the Friday close.";
                case "InsideBarBreakout" -> "Breakout of inside bars filtered by a Bollinger Bandwidth volatility squeeze.";
                case "OverlapMomentumBurst" -> "Breakout of the London-NY overlap range (10:00–12:00 UTC).";
                default -> "Built-in prop mechanical strategy.";
            };
        }
        if (family == Family.SQ_IMPORTED) {
            return "Machine-generated strategy imported from StrategyQuant.";
        }
        if (id.equals("SmaCrossover")) {
            return "Simple SMA crossover strategy (20/50 fast/slow SMA crossover).";
        }
        return "Custom or weekly compiled strategy.";
    }

    private static void bootstrapBuiltIn() {
        PropStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.PROP, PropStrategyCatalog.defaultSymbol(id),
                sym -> PropStrategyCatalog.create(id, sym)));
        SqImportedStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.SQ_IMPORTED, SqImportedStrategyCatalog.defaultSymbol(id),
                sym -> SqImportedStrategyCatalog.create(id)));
        GeneratedStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.GENERATED, GeneratedStrategyCatalog.defaultSymbol(id),
                sym -> GeneratedStrategyCatalog.create(id, sym)));
        LlmWeeklyStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.LLM_WEEKLY, LlmWeeklyStrategyCatalog.defaultSymbol(id),
                sym -> LlmWeeklyStrategyCatalog.create(id, sym)));
        NewsWeeklyStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.NEWS_WEEKLY, NewsWeeklyStrategyCatalog.defaultSymbol(id),
                sym -> NewsWeeklyStrategyCatalog.create(id, sym)));
        HarnessStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.HARNESS, HarnessStrategyCatalog.defaultSymbol(id),
                sym -> HarnessStrategyCatalog.create(id, sym)));
    }

    /** Runtime registration for example strategies (called from RunBacktest). */
    public static synchronized void register(String id, Family family, Function<String, Strategy> factory, String defaultSymbol) {
        if (family != Family.EXAMPLE) {
            throw new IllegalArgumentException("register() is for EXAMPLE family only; built-ins use sub-catalogs");
        }
        put(id, family, defaultSymbol, factory);
    }

    public static Strategy create(String id, String symbol) {
        return create(id, symbol, null);
    }

    public static Strategy create(String id, String symbol, Double quantityUnits) {
        Registration reg = ENTRIES.get(id);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown strategy: " + id);
        }
        Strategy strategy = reg.factory().apply(symbol);
        return new FixedQuantityStrategy(strategy, com.martinfou.trading.core.LotSizing.resolveQuantityUnits(quantityUnits));
    }

    public static String defaultSymbol(String id) {
        Registration reg = ENTRIES.get(id);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown strategy: " + id);
        }
        return reg.defaultSymbol();
    }

    public static Family family(String id) {
        Registration reg = ENTRIES.get(id);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown strategy: " + id);
        }
        return reg.family();
    }

    public static List<String> ids() {
        return List.copyOf(ENTRIES.keySet());
    }

    public static List<Entry> entries() {
        var list = new ArrayList<Entry>(ENTRIES.size());
        ENTRIES.forEach((id, reg) -> list.add(new Entry(
            id,
            reg.family(),
            reg.defaultSymbol(),
            reg.type(),
            reg.indicators(),
            reg.description()
        )));
        return List.copyOf(list);
    }

    public static boolean contains(String id) {
        return ENTRIES.containsKey(id);
    }

    /** Test-only: reset to built-in registrations (clears EXAMPLE entries). */
    public static synchronized void resetForTesting() {
        ENTRIES.clear();
        bootstrapBuiltIn();
    }

    private static synchronized void put(String id, Family family, String defaultSymbol, Function<String, Strategy> factory) {
        if (ENTRIES.containsKey(id)) {
            throw new IllegalStateException("Duplicate strategy id: " + id);
        }
        String type = resolveType(id, family);
        List<String> indicators = resolveIndicators(id, family);
        String description = resolveDescription(id, family);
        ENTRIES.put(id, new Registration(family, defaultSymbol, type, indicators, description, factory));
    }
}
