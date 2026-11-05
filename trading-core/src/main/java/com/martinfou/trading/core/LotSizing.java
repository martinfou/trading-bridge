package com.martinfou.trading.core;

import java.util.Locale;
import java.util.Set;

/** Multi-asset lot, unit, and contract sizing and conversion. */
public final class LotSizing {

    public static final double UNITS_PER_STANDARD_LOT = 100_000.0;
    public static final double UNITS_PER_XAU_LOT = 100.0;
    public static final double UNITS_PER_XAG_LOT = 5_000.0;
    public static final double DEFAULT_LOT_SIZE = 0.01;
    public static final double DEFAULT_QUANTITY_UNITS = lotsToUnits(DEFAULT_LOT_SIZE);
    public static final double DEFAULT_STARTING_CAPITAL = 1_000.0;

    private static final Set<String> MICRO_FUTURES = Set.of(
        "MGC", "MNQ", "MES", "M2K", "MYM", "MCL"
    );

    public record InstrumentDefaults(
        double capital,
        double lotSize,
        double commissionPerTrade,
        double slippagePct
    ) {}

    private LotSizing() {}

    public static boolean isMicroFutures(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String norm = symbol.trim().toUpperCase(Locale.ROOT).replace('/', '_');
        for (String micro : MICRO_FUTURES) {
            if (norm.equals(micro) || norm.startsWith(micro + "_")) {
                return true;
            }
        }
        return false;
    }

    public static InstrumentDefaults defaultsForSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return new InstrumentDefaults(10_000.0, 0.10, 0.70, 0.00005);
        }
        AssetClass assetClass = AssetValuationRegistry.resolve(symbol).assetClass();
        if (assetClass == AssetClass.FUTURES) {
            if (isMicroFutures(symbol)) {
                return new InstrumentDefaults(10_000.0, 1.0, 0.62, 0.00005);
            }
            return new InstrumentDefaults(50_000.0, 1.0, 2.10, 0.00005);
        }
        if (assetClass == AssetClass.EQUITY) {
            return new InstrumentDefaults(10_000.0, 10.0, 1.00, 0.0002);
        }
        return new InstrumentDefaults(10_000.0, 0.10, 0.70, 0.00005);
    }

    private static double unitsPerLotForForexOrMetal(String symbol) {
        if (symbol == null) {
            return UNITS_PER_STANDARD_LOT;
        }
        String norm = symbol.trim().toUpperCase(Locale.ROOT).replace('/', '_');
        if (norm.startsWith("XAU")) {
            return UNITS_PER_XAU_LOT;
        }
        if (norm.startsWith("XAG")) {
            return UNITS_PER_XAG_LOT;
        }
        return UNITS_PER_STANDARD_LOT;
    }

    public static double lotsToUnits(double lots) {
        return lots * UNITS_PER_STANDARD_LOT;
    }

    public static double lotsToUnits(double lots, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return lotsToUnits(lots);
        }
        AssetClass assetClass = AssetValuationRegistry.resolve(symbol).assetClass();
        if (assetClass == AssetClass.FUTURES || assetClass == AssetClass.EQUITY) {
            return lots; // 1.0 in futures = 1 contract; 10 in equities = 10 shares
        }
        return lots * unitsPerLotForForexOrMetal(symbol);
    }

    public static double unitsToLots(double units) {
        return units / UNITS_PER_STANDARD_LOT;
    }

    public static double unitsToLots(double units, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return unitsToLots(units);
        }
        AssetClass assetClass = AssetValuationRegistry.resolve(symbol).assetClass();
        if (assetClass == AssetClass.FUTURES || assetClass == AssetClass.EQUITY) {
            return units;
        }
        return units / unitsPerLotForForexOrMetal(symbol);
    }

    public static double resolveQuantityUnits(Double quantityUnits) {
        if (quantityUnits != null && quantityUnits > 0) {
            return quantityUnits;
        }
        return DEFAULT_QUANTITY_UNITS;
    }

    public static double resolveQuantityUnits(Double quantityUnits, String symbol) {
        if (quantityUnits != null && quantityUnits > 0) {
            return quantityUnits;
        }
        if (symbol == null || symbol.isBlank()) {
            return DEFAULT_QUANTITY_UNITS;
        }
        AssetClass assetClass = AssetValuationRegistry.resolve(symbol).assetClass();
        if (assetClass == AssetClass.FUTURES) {
            return 1.0; // 1 discrete contract
        } else if (assetClass == AssetClass.EQUITY) {
            return 10.0; // 10 shares
        }
        String norm = symbol.trim().toUpperCase(Locale.ROOT).replace('/', '_');
        if (norm.startsWith("XAU")) {
            return 10.0; // 0.10 lot = 10 oz
        }
        if (norm.startsWith("XAG")) {
            return 500.0; // 0.10 lot = 500 oz
        }
        return DEFAULT_QUANTITY_UNITS;
    }

    public static double resolveQuantityFromLots(Double lotSize) {
        if (lotSize != null && lotSize > 0) {
            return lotsToUnits(lotSize);
        }
        return DEFAULT_QUANTITY_UNITS;
    }

    public static double resolveQuantityFromLots(Double lotSize, String symbol) {
        if (lotSize != null && lotSize > 0) {
            return lotsToUnits(lotSize, symbol);
        }
        InstrumentDefaults def = defaultsForSymbol(symbol);
        return lotsToUnits(def.lotSize(), symbol);
    }

    public static double resolveCapital(Double capital) {
        if (capital != null && capital > 0) {
            return capital;
        }
        return DEFAULT_STARTING_CAPITAL;
    }

    public static double resolveCapital(Double capital, String symbol) {
        if (capital != null && capital > 0) {
            return capital;
        }
        if (symbol == null || symbol.isBlank()) {
            return DEFAULT_STARTING_CAPITAL;
        }
        AssetClass assetClass = AssetValuationRegistry.resolve(symbol).assetClass();
        if (assetClass == AssetClass.FUTURES) {
            return isMicroFutures(symbol) ? 10_000.0 : 50_000.0;
        } else if (assetClass == AssetClass.EQUITY) {
            return 10_000.0;
        }
        return DEFAULT_STARTING_CAPITAL;
    }
}
