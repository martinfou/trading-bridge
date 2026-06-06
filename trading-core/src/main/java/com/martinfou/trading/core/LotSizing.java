package com.martinfou.trading.core;

/** Forex lot ↔ unit conversion (1 standard lot = 100,000 units). */
public final class LotSizing {

    public static final double UNITS_PER_STANDARD_LOT = 100_000.0;
    public static final double DEFAULT_LOT_SIZE = 0.01;
    public static final double DEFAULT_QUANTITY_UNITS = lotsToUnits(DEFAULT_LOT_SIZE);
    public static final double DEFAULT_STARTING_CAPITAL = 1_000.0;

    private LotSizing() {}

    public static double lotsToUnits(double lots) {
        return lots * UNITS_PER_STANDARD_LOT;
    }

    public static double unitsToLots(double units) {
        return units / UNITS_PER_STANDARD_LOT;
    }

    public static double resolveQuantityUnits(Double quantityUnits) {
        if (quantityUnits != null && quantityUnits > 0) {
            return quantityUnits;
        }
        return DEFAULT_QUANTITY_UNITS;
    }

    public static double resolveQuantityFromLots(Double lotSize) {
        if (lotSize != null && lotSize > 0) {
            return lotsToUnits(lotSize);
        }
        return DEFAULT_QUANTITY_UNITS;
    }

    public static double resolveCapital(Double capital) {
        if (capital != null && capital > 0) {
            return capital;
        }
        return DEFAULT_STARTING_CAPITAL;
    }
}
