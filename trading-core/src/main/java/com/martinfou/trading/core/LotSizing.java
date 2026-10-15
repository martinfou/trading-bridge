package com.martinfou.trading.core;

/** Multi-asset lot, unit, and contract sizing and conversion. */
public final class LotSizing {

    public static final double UNITS_PER_STANDARD_LOT = 100_000.0;
    public static final double DEFAULT_LOT_SIZE = 0.01;
    public static final double DEFAULT_QUANTITY_UNITS = lotsToUnits(DEFAULT_LOT_SIZE);
    public static final double DEFAULT_STARTING_CAPITAL = 1_000.0;

    private LotSizing() {}

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
        return lots * UNITS_PER_STANDARD_LOT;
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
        return units / UNITS_PER_STANDARD_LOT;
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
        return resolveQuantityUnits(null, symbol);
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
            return 50_000.0;
        } else if (assetClass == AssetClass.EQUITY) {
            return 10_000.0;
        }
        return DEFAULT_STARTING_CAPITAL;
    }
}
