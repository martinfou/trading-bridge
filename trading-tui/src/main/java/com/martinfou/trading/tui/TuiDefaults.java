package com.martinfou.trading.tui;

import java.util.Locale;
import java.util.Set;

/**
 * Default backtest and generator sizing presets per instrument / asset class.
 * Keeps legacy constants for unit test compatibility while providing institutional Smart Instrument Defaults.
 */
public final class TuiDefaults {

    static final double STARTING_CAPITAL = 1_000.0;
    static final double LOT_SIZE = 0.01;

    private static final Set<String> CME_MICROS = Set.of(
        "MES", "MNQ", "MGC", "M2K", "MCL", "MYM"
    );

    private static final Set<String> CME_FULL = Set.of(
        "ES", "NQ", "GC", "EMD", "CL", "YM", "RTY", "SI", "HG", "ZB", "ZN"
    );

    private static final Set<String> KNOWN_FOREX_METALS = Set.of(
        "EUR_USD", "GBP_USD", "GBP_JPY", "USD_CAD", "USD_JPY", "AUD_USD", "NZD_USD", "USD_CHF",
        "EUR_GBP", "EUR_JPY", "AUD_JPY", "CAD_JPY", "CHF_JPY", "EUR_AUD", "GBP_AUD",
        "XAU_USD", "XAG_USD"
    );

    public record SmartPreset(
        String assetClass,
        double capital,
        double lotSize,
        double commissionPerTrade,
        double slippagePct
    ) {}

    public static SmartPreset resolvePreset(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return new SmartPreset("FOREX", 10_000.0, 0.10, 0.70, 0.00005);
        }
        String sym = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (CME_MICROS.contains(sym)) {
            return new SmartPreset("FUTURES", 10_000.0, 1.0, 0.62, 0.00005);
        }
        if (CME_FULL.contains(sym)) {
            return new SmartPreset("FUTURES", 50_000.0, 1.0, 2.10, 0.00005);
        }
        if (KNOWN_FOREX_METALS.contains(sym) || sym.contains("_") || sym.matches("[A-Z]{6}")) {
            return new SmartPreset("FOREX", 10_000.0, 0.10, 0.70, 0.00005);
        }
        return new SmartPreset("EQUITIES", 10_000.0, 10.0, 1.00, 0.0002);
    }

    private TuiDefaults() {}
}
