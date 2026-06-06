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
        PROP, SQ_IMPORTED, GENERATED, LLM_WEEKLY, NEWS_WEEKLY, HARNESS, EXAMPLE
    }

    public record Entry(String id, Family family, String defaultSymbol) {}

    private record Registration(Family family, String defaultSymbol, Function<String, Strategy> factory) {}

    private static final Map<String, Registration> ENTRIES = new LinkedHashMap<>();

    static {
        bootstrapBuiltIn();
    }

    private StrategyCatalog() {}

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
        ENTRIES.forEach((id, reg) -> list.add(new Entry(id, reg.family(), reg.defaultSymbol())));
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
        ENTRIES.put(id, new Registration(family, defaultSymbol, factory));
    }
}
