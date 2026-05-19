package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CorrelationMatrix}.
 */
class CorrelationMatrixTest {

    @Test
    void pearson_perfectPositive_returns1() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {2, 4, 6, 8, 10};
        assertEquals(1.0, CorrelationMatrix.pearson(x, y), 1e-10);
    }

    @Test
    void pearson_perfectNegative_returnsMinus1() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {10, 8, 6, 4, 2};
        assertEquals(-1.0, CorrelationMatrix.pearson(x, y), 1e-10);
    }

    @Test
    void pearson_noCorrelation_returnsNear0() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {5, 1, 8, 2, 3};
        double r = CorrelationMatrix.pearson(x, y);
        assertTrue(Math.abs(r) < 0.5);
    }

    @Test
    void pearson_tooFewPoints_returnsNaN() {
        double[] x = {1};
        double[] y = {2};
        assertTrue(Double.isNaN(CorrelationMatrix.pearson(x, y)));
    }

    @Test
    void computePnLCorrelation_threeStrategies_producesThreePairs() {
        List<Double> ec1 = List.of(10000.0, 10100.0, 10200.0, 10300.0, 10400.0);
        List<Double> ec2 = List.of(10000.0, 9900.0, 10050.0, 9950.0, 10100.0);
        List<Double> ec3 = List.of(10000.0, 10010.0, 10020.0, 10030.0, 10040.0);

        BacktestResult r1 = resultAt("A", ec1);
        BacktestResult r2 = resultAt("B", ec2);
        BacktestResult r3 = resultAt("C", ec3);

        CorrelationMatrix cm = new CorrelationMatrix(Map.of("A", r1, "B", r2, "C", r3));
        List<CorrelationMatrix.Pair> pairs = cm.computePnLCorrelation();

        // Three strategies → three unique pairs
        assertEquals(3, pairs.size());

        // Self-correlation should be near 1
        // A↔A and B↔B and C↔C should be 1.0 (but they're not in the pair list)
        // We can verify via matrix
        double[][] matrix = cm.toMatrix();
        assertEquals(3, matrix.length);
        assertEquals(1.0, matrix[0][0], 1e-10);
        assertEquals(1.0, matrix[1][1], 1e-10);
        assertEquals(1.0, matrix[2][2], 1e-10);
    }

    @Test
    void computeDrawdownCorrelation_sameCurve_returns1() {
        List<Double> ec = List.of(10000.0, 10500.0, 9500.0, 10200.0, 9000.0);
        BacktestResult r1 = resultAt("A", ec);
        BacktestResult r2 = resultAt("B", ec);

        CorrelationMatrix cm = new CorrelationMatrix(Map.of("A", r1, "B", r2));
        List<CorrelationMatrix.Pair> ddPairs = cm.computeDrawdownCorrelation();

        assertEquals(1, ddPairs.size());
        // Same curves → drawdown overlap should be 1.0
        assertEquals(1.0, ddPairs.getFirst().correlation(), 1e-10);
    }

    @Test
    void drawdownFlags_knownCurve() {
        List<Double> ec = List.of(10000.0, 10500.0, 9500.0, 10200.0);
        boolean[] flags = CorrelationMatrix.computeDrawdownFlags(ec);

        // [10000] — first is peak → not in DD
        assertFalse(flags[0]);
        // [10500] — new peak → not in DD
        assertFalse(flags[1]);
        // [9500] — below 10500 → in DD
        assertTrue(flags[2]);
        // [10200] — still below 10500 → in DD
        assertTrue(flags[3]);
    }

    @Test
    void drawdownFlags_empty_returnsEmpty() {
        boolean[] flags = CorrelationMatrix.computeDrawdownFlags(List.of());
        assertEquals(0, flags.length);
    }

    @Test
    void printMethods_doNotThrow() {
        List<Double> ec = List.of(10000.0, 10100.0, 10200.0);
        BacktestResult r1 = resultAt("A", ec);
        BacktestResult r2 = resultAt("B", ec);

        CorrelationMatrix cm = new CorrelationMatrix(Map.of("A", r1, "B", r2));
        assertDoesNotThrow(cm::printPnLMatrix);
        assertDoesNotThrow(cm::printDrawdownMatrix);
    }

    @Test
    void singleStrategy_returnsEmptyPairs() {
        List<Double> ec = List.of(10000.0, 10100.0);
        BacktestResult r1 = resultAt("A", ec);
        CorrelationMatrix cm = new CorrelationMatrix(Map.of("A", r1));
        List<CorrelationMatrix.Pair> pairs = cm.computePnLCorrelation();
        assertTrue(pairs.isEmpty());
    }

    // ---------------------------------------------------------------
    //  Helper
    // ---------------------------------------------------------------

    private static BacktestResult resultAt(String name, List<Double> equityCurve) {
        return BacktestResult.builder()
            .strategyName(name)
            .initialCapital(equityCurve.getFirst())
            .finalEquity(equityCurve.getLast())
            .equityCurve(equityCurve)
            .trades(List.of())
            .periodStart(Instant.parse("2026-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2026-06-01T00:00:00Z"))
            .build();
    }
}
