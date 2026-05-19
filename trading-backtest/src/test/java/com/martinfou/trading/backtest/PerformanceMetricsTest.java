package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PerformanceMetrics}.
 */
class PerformanceMetricsTest {

    @Test
    void sharpeRatio_constantReturns_returnsZero() {
        List<Double> returns = List.of(0.01, 0.01, 0.01, 0.01);
        assertEquals(0.0, PerformanceMetrics.sharpeRatio(returns, 0.01), 1e-10);
    }

    @Test
    void sharpeRatio_positiveReturns_positiveSharpe() {
        // All returns > 0 and positive mean after risk-free
        List<Double> returns = List.of(0.01, 0.02, 0.015, 0.018, 0.012);
        double sharpe = PerformanceMetrics.sharpeRatio(returns, 0.005);
        assertTrue(sharpe > 0, "Positive returns should yield positive Sharpe");
    }

    @Test
    void sharpeRatio_emptyList_returnsZero() {
        assertEquals(0.0, PerformanceMetrics.sharpeRatio(List.of(), 0.01));
    }

    @Test
    void sharpeRatio_singleElement_returnsZero() {
        assertEquals(0.0, PerformanceMetrics.sharpeRatio(List.of(0.01), 0.01));
    }

    @Test
    void sortinoRatio_positiveReturns_nonNegative() {
        // All returns above target → downside deviation is 0 → Sortino = 0 or positive
        List<Double> returns = List.of(0.01, 0.02, 0.015, 0.018, 0.012);
        double sortino = PerformanceMetrics.sortinoRatio(returns, 0.005);
        assertTrue(sortino >= 0, "Positive returns should yield non-negative Sortino");
    }

    @Test
    void sortinoRatio_mixedReturns_finite() {
        List<Double> returns = List.of(0.02, -0.01, 0.03, -0.02, 0.01);
        double sortino = PerformanceMetrics.sortinoRatio(returns, 0.01);
        assertFalse(Double.isNaN(sortino));
        assertFalse(Double.isInfinite(sortino));
    }

    @Test
    void profitFactor_allWinners_returnsGrossProfit() {
        List<Double> pnls = List.of(100.0, 200.0, 50.0);
        assertEquals(350.0, PerformanceMetrics.profitFactor(pnls), 1e-10);
    }

    @Test
    void profitFactor_mixed_correctValue() {
        List<Double> pnls = List.of(100.0, -50.0, 200.0, -30.0);
        // grossProfit = 300, grossLoss = 80 → 300/80 = 3.75
        assertEquals(3.75, PerformanceMetrics.profitFactor(pnls), 1e-10);
    }

    @Test
    void profitFactor_empty_returnsZero() {
        assertEquals(0.0, PerformanceMetrics.profitFactor(List.of()));
    }

    @Test
    void profitFactor_null_returnsZero() {
        assertEquals(0.0, PerformanceMetrics.profitFactor(null));
    }

    @Test
    void calmarRatio_basic_correctValue() {
        List<Double> equity = List.of(10000.0, 10500.0, 9500.0, 11000.0);
        double calmar = PerformanceMetrics.calmarRatio(equity);
        // Should be positive since annualised return is positive
        assertTrue(calmar > 0);
    }

    @Test
    void calmarRatio_smallInput_returnsZero() {
        assertEquals(0.0, PerformanceMetrics.calmarRatio(List.of(10000.0)));
    }

    @Test
    void averageTrade_correctValue() {
        List<Double> pnls = List.of(100.0, -50.0, 200.0);
        assertEquals(250.0 / 3, PerformanceMetrics.averageTrade(pnls), 1e-10);
    }

    @Test
    void averageTrade_empty_returnsZero() {
        assertEquals(0.0, PerformanceMetrics.averageTrade(List.of()));
    }

    @Test
    void annualisedReturn_positiveGrowth_positive() {
        List<Double> equity = List.of(10000.0, 11000.0, 12100.0);
        double ann = PerformanceMetrics.annualisedReturn(equity);
        // 10000 → 12100 over 2 periods. With 252 periods/year:
        // (12100/10000)^(252/2) - 1 should be a very large number
        assertTrue(ann > 0);
    }

    @Test
    void standardDeviation_knownValues() {
        List<Double> values = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        double std = PerformanceMetrics.standardDeviation(values);
        // Sample std dev = sqrt(10/4) = sqrt(2.5) ≈ 1.5811
        assertEquals(Math.sqrt(2.5), std, 1e-10);
    }

    @Test
    void mean_basic() {
        List<Double> values = List.of(1.0, 2.0, 3.0);
        assertEquals(2.0, PerformanceMetrics.mean(values), 1e-10);
    }

    @Test
    void downsideDeviation_knownValues() {
        List<Double> returns = List.of(0.01, -0.02, 0.03, -0.01, 0.02);
        double dd = PerformanceMetrics.downsideDeviation(returns);
        assertTrue(dd > 0, "Downside deviation should be > 0");
        assertFalse(Double.isNaN(dd));
    }

    @Test
    void maxDrawdownDecimal_known() {
        List<Double> equity = List.of(10000.0, 11000.0, 9000.0, 10500.0);
        double dd = PerformanceMetrics.maxDrawdownDecimal(equity);
        // Peak = 11000, trough = 9000, dd = 2000/11000 ≈ 0.1818
        assertEquals((11000.0 - 9000.0) / 11000.0, dd, 1e-10);
    }

    @Test
    void defaultRiskFreeRate_isPositive() {
        assertTrue(PerformanceMetrics.DEFAULT_RISK_FREE_RATE > 0);
    }

    @Test
    void periodsPerYear_isReasonable() {
        assertEquals(252.0, PerformanceMetrics.PERIODS_PER_YEAR);
    }
}
