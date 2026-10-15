package com.martinfou.trading.core;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for CME and multi-asset Futures contracts, loaded from data/runtime/futures-contracts.json
 * with deterministic built-in fallbacks for CME micros/minis (MES, M2K, EMD, MNQ).
 */
public final class FuturesRegistry {

    private static final Map<String, FuturesContract> CONTRACTS = new ConcurrentHashMap<>();

    static {
        registerDefaults();
    }

    private FuturesRegistry() {}

    private static void registerDefaults() {
        register(new FuturesContract("MES", "Micro E-mini S&P 500", "CME", "USD", 5.0, 0.25, 1.25, 1200.0, 1000.0));
        register(new FuturesContract("M2K", "Micro E-mini Russell 2000", "CME", "USD", 5.0, 0.10, 0.50, 800.0, 650.0));
        register(new FuturesContract("EMD", "E-mini S&P MidCap 400", "CME", "USD", 100.0, 0.10, 10.0, 4500.0, 3800.0));
        register(new FuturesContract("MNQ", "Micro E-mini Nasdaq 100", "CME", "USD", 2.0, 0.25, 0.50, 1800.0, 1500.0));
    }

    public static void register(FuturesContract contract) {
        if (contract != null) {
            CONTRACTS.put(normalizeSymbol(contract.symbol()), contract);
        }
    }

    public static Optional<FuturesContract> find(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeSymbol(symbol);
        FuturesContract exact = CONTRACTS.get(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        // Check prefix (e.g. MES_202412, MESZ4, etc.)
        for (Map.Entry<String, FuturesContract> entry : CONTRACTS.entrySet()) {
            if (normalized.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    public static boolean isFutures(String symbol) {
        return find(symbol).isPresent();
    }

    public static double quantizePrice(String symbol, double price) {
        return find(symbol)
            .map(contract -> contract.quantizePrice(price))
            .orElse(price);
    }

    public static boolean isValidTick(String symbol, double price) {
        return find(symbol)
            .map(contract -> contract.isValidTick(price))
            .orElse(true);
    }

    public static Map<String, FuturesContract> all() {
        return Map.copyOf(CONTRACTS);
    }

    public static String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        return symbol.trim().toUpperCase().replace('/', '_');
    }
}

