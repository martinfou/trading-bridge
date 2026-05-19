package com.martinfou.trading.backtest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PortfolioBuilder}.
 */
class PortfolioBuilderTest {

    @Test
    void covariance_knownValues() {
        double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] y = {5.0, 4.0, 3.0, 2.0, 1.0};
        double cov = PortfolioBuilder.covariance(x, y);
        // With sample covariance: x mean=3, y mean=3
        // sum((xi-3)(yi-3)) = (-2)(2)+(-1)(1)+(0)(0)+(1)(-1)+(2)(-2) = -4-1+0-1-4 = -10
        // cov = -10/4 = -2.5
        assertEquals(-2.5, cov, 1e-10);
    }

    @Test
    void equalWeightPortfolio_returns1overN() {
        BacktestResult r1 = resultFromReturns("A", List.of(0.01, 0.02, 0.015));
        BacktestResult r2 = resultFromReturns("B", List.of(0.005, 0.01, 0.008));
        BacktestResult r3 = resultFromReturns("C", List.of(0.02, 0.01, 0.02));

        PortfolioBuilder pb = new PortfolioBuilder(Map.of("A", r1, "B", r2, "C", r3));
        PortfolioBuilder.Portfolio eq = pb.equalWeightPortfolio();

        assertNotNull(eq);
        assertEquals(3, eq.weights().size());
        eq.weights().values().forEach(w -> assertEquals(1.0 / 3, w, 1e-10));
    }

    @Test
    void minVariancePortfolio_weightsSumToOne() {
        BacktestResult r1 = resultFromReturns("A", List.of(0.01, 0.015, 0.012, 0.018));
        BacktestResult r2 = resultFromReturns("B", List.of(0.008, 0.009, 0.007, 0.011));
        BacktestResult r3 = resultFromReturns("C", List.of(0.02, 0.005, 0.025, 0.01));

        PortfolioBuilder pb = new PortfolioBuilder(Map.of("A", r1, "B", r2, "C", r3));
        PortfolioBuilder.Portfolio mv = pb.minVariancePortfolio();

        assertNotNull(mv);
        double sum = mv.weights().values().stream().mapToDouble(d -> d).sum();
        assertEquals(1.0, sum, 0.01);
    }

    @Test
    void maxSharpePortfolio_weightsSumToOne() {
        BacktestResult r1 = resultFromReturns("A", List.of(0.01, 0.02, 0.015, 0.018));
        BacktestResult r2 = resultFromReturns("B", List.of(0.005, 0.008, 0.006, 0.01));

        PortfolioBuilder pb = new PortfolioBuilder(Map.of("A", r1, "B", r2));
        PortfolioBuilder.Portfolio ms = pb.maxSharpePortfolio();

        assertNotNull(ms);
        double sum = ms.weights().values().stream().mapToDouble(d -> d).sum();
        assertEquals(1.0, sum, 0.01);
    }

    @Test
    void efficientFrontier_singleStrategy_returnsEmpty() {
        BacktestResult r1 = resultFromReturns("A", List.of(0.01, 0.02, 0.015));
        PortfolioBuilder pb = new PortfolioBuilder(Map.of("A", r1));
        var frontier = pb.efficientFrontier(10);
        assertTrue(frontier.isEmpty());
    }

    @Test
    void efficientFrontier_twoStrategies_producesPoints() {
        BacktestResult r1 = resultFromReturns("A", List.of(0.01, 0.02, 0.015, 0.018));
        BacktestResult r2 = resultFromReturns("B", List.of(0.005, 0.008, 0.006, 0.01));

        PortfolioBuilder pb = new PortfolioBuilder(Map.of("A", r1, "B", r2));
        var frontier = pb.efficientFrontier(10);

        assertEquals(10, frontier.size());
        // Lower return → lower risk
        assertTrue(frontier.getFirst().returnPct() <= frontier.getLast().returnPct());
        assertTrue(frontier.getFirst().riskPct() <= frontier.getLast().riskPct());
    }

    @Test
    void printMethods_doNotThrow() {
        BacktestResult r1 = resultFromReturns("A", List.of(0.01, 0.02, 0.015));
        BacktestResult r2 = resultFromReturns("B", List.of(0.005, 0.01, 0.008));

        PortfolioBuilder pb = new PortfolioBuilder(Map.of("A", r1, "B", r2));
        assertDoesNotThrow(() -> pb.equalWeightPortfolio().printSummary());
        assertDoesNotThrow(() -> pb.printEfficientFrontier(5));
    }

    @Test
    void covariance_identical_positive() {
        double[] x = {1.0, 2.0, 3.0};
        double[] y = {1.0, 2.0, 3.0};
        assertTrue(PortfolioBuilder.covariance(x, y) > 0);
    }

    @Test
    void portfolio_riskFreeReturns_finiteSharpe() {
        // Strategies with all returns = risk-free (0%) → Sharpe should be 0
        BacktestResult r1 = resultFromReturns("A", List.of(0.0, 0.0, 0.0));
        BacktestResult r2 = resultFromReturns("B", List.of(0.0, 0.0, 0.0));

        PortfolioBuilder pb = new PortfolioBuilder(Map.of("A", r1, "B", r2));
        PortfolioBuilder.Portfolio eq = pb.equalWeightPortfolio();

        assertFalse(Double.isNaN(eq.sharpeRatio()));
        assertFalse(Double.isInfinite(eq.sharpeRatio()));
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static BacktestResult resultFromReturns(String name, List<Double> periodReturns) {
        // Build equity curve from period returns
        List<Double> equity = new java.util.ArrayList<>();
        double eq = 10000.0;
        equity.add(eq);
        for (double r : periodReturns) {
            eq *= (1 + r);
            equity.add(eq);
        }

        return BacktestResult.builder()
            .strategyName(name)
            .initialCapital(10000.0)
            .finalEquity(eq)
            .totalPnl(eq - 10000)
            .totalTrades(periodReturns.size())
            .equityCurve(equity)
            .sharpeRatio(PerformanceMetrics.sharpeRatio(periodReturns))
            .trades(List.of())
            .periodStart(Instant.parse("2026-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2026-06-01T00:00:00Z"))
            .build();
    }
}
