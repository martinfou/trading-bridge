package com.martinfou.trading.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the risk-budget sizing rule (1% class) against the live figures audited on 2026-09-29:
 * a CAD practice account of 96,302.12, a GBP/JPY stop of 41.1 pips, USD/CAD 1.375, USD/JPY 154.8.
 */
class RiskSizingTest {

    private static final double NAV = 96302.12;                 // CAD, live practice NAV
    /** Broker's own JPY→CAD loss factor (homeConversions.accountLoss), read read-only 2026-09-29. */
    private static final double JPY_TO_CAD = 0.009153276709;
    private static final double GBPJPY_STOP = 207.391 - 206.980; // 0.411 = 41.1 pips
    private static final double USD_TO_CAD = 1.375;

    private static long expected(double equity, double riskPct, double stop, double factor) {
        double units = (equity * riskPct / 100.0) / (stop * factor);
        return Math.round(units / 100.0) * 100;
    }

    @Test
    @DisplayName("GBP/JPY at 0.75% of NAV matches the audited target (consecbar)")
    void gbpJpyConsecbarRisk() {
        long units = RiskSizing.unitsForRisk(NAV, 0.75, GBPJPY_STOP, JPY_TO_CAD, RiskSizing.ROUND_TO_UNITS);
        assertEquals(expected(NAV, 0.75, GBPJPY_STOP, JPY_TO_CAD), units);
        assertEquals(192_000, units, "audited: 0.75% of 96,302 CAD at a 41.1 pip stop");
    }

    @Test
    @DisplayName("GBP/JPY at 1% of NAV is ~256k units, not the 1,000 sized live")
    void gbpJpyOnePercentIsTwoHundredFiftySixThousandUnits() {
        long units = RiskSizing.unitsForRisk(NAV, 1.0, GBPJPY_STOP, JPY_TO_CAD, RiskSizing.ROUND_TO_UNITS);
        assertEquals(256_000, units);
        assertTrue(units > 250_000, "the deployed 1,000 units under-risked by ~255x");
    }

    @Test
    @DisplayName("Risk actually carried equals the budget (round-trip check)")
    void riskAmountMatchesBudget() {
        long units = RiskSizing.unitsForRisk(NAV, 0.75, GBPJPY_STOP, JPY_TO_CAD, RiskSizing.ROUND_TO_UNITS);
        double risk = RiskSizing.riskAmount(units, GBPJPY_STOP, JPY_TO_CAD);
        assertEquals(NAV * 0.0075, risk, NAV * 0.0075 * 0.01, "within 1% of the 722.27 CAD budget");
        // the deployed 1,000 units carried 3.77 CAD = 0.0039% of NAV
        assertEquals(0.0039, RiskSizing.riskAmount(1_000, GBPJPY_STOP, JPY_TO_CAD) / NAV * 100, 0.0005);
    }

    @Test
    @DisplayName("USD-quoted pair on a CAD account uses the CAD factor, not 1.0")
    void usdQuotedPairUsesConversion() {
        double stop = 0.0020; // 20 pips EUR_USD
        long withFactor = RiskSizing.unitsForRisk(NAV, 1.0, stop, USD_TO_CAD, RiskSizing.ROUND_TO_UNITS);
        long naive = RiskSizing.unitsForRisk(NAV, 1.0, stop, 1.0, RiskSizing.ROUND_TO_UNITS);
        assertEquals(350_200, withFactor);
        assertEquals(1.375, (double) naive / withFactor, 0.01, "omitting the factor is 1.38x too loose");
    }

    @Test
    @DisplayName("Same-currency account and instrument (USD/USD) sizes on the raw stop")
    void sameCurrencyNeedsNoConversion() {
        assertEquals(50_000, RiskSizing.unitsForRisk(10_000, 1.0, 0.0020, 1.0, RiskSizing.ROUND_TO_UNITS));
    }

    @Test
    @DisplayName("Undefined budgets return 0 instead of inventing a size")
    void undefinedInputsReturnZero() {
        assertEquals(0, RiskSizing.unitsForRisk(NAV, 1.0, 0.0, JPY_TO_CAD, 100), "no stop distance");
        assertEquals(0, RiskSizing.unitsForRisk(0, 1.0, GBPJPY_STOP, JPY_TO_CAD, 100), "no equity");
        assertEquals(0, RiskSizing.unitsForRisk(NAV, 0.0, GBPJPY_STOP, JPY_TO_CAD, 100), "no risk budget");
        assertEquals(0, RiskSizing.unitsForRisk(NAV, 1.0, GBPJPY_STOP, 0.0, 100), "no conversion factor");
        assertEquals(0, RiskSizing.unitsForRisk(NAV, 1.0, -1.0, JPY_TO_CAD, 100), "negative distance");
    }

    @Test
    @DisplayName("Tiny budgets floor at the broker minimum instead of rounding to zero")
    void floorsAtBrokerMinimum() {
        assertEquals(RiskSizing.MIN_UNITS,
            RiskSizing.unitsForRisk(100, 0.01, 0.411, JPY_TO_CAD, RiskSizing.ROUND_TO_UNITS));
    }
}
