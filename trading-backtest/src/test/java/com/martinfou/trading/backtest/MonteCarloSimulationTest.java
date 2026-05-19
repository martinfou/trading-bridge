package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Trade;
import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MonteCarloSimulation}.
 */
class MonteCarloSimulationTest {

    @Test
    void run_withTrades_producesResult() {
        BacktestResult baseline = createBaselineResult(100, 10000.0);
        MonteCarloSimulation mc = new MonteCarloSimulation(baseline, 100);
        MonteCarloSimulation.Result result = mc.run();

        assertNotNull(result);
        assertEquals(100, result.totalRuns());
        assertEquals(100, result.pnlValuesSorted().size());
        assertEquals(100, result.drawdownValuesSorted().size());
        assertEquals(100, result.sharpeValuesSorted().size());
    }

    @Test
    void run_medianPnl_approachesBaseline() {
        // With many trades, the median should be close to the sum of all P&Ls
        double expectedPnl = 500.0;
        BacktestResult baseline = createBaselineResult(50, 10000.0, expectedPnl / 50);
        MonteCarloSimulation mc = new MonteCarloSimulation(baseline, 500);
        MonteCarloSimulation.Result result = mc.run();

        // Median should be in the ballpark of expected total P&L
        assertTrue(result.medianPnl() > expectedPnl * 0.5);
        assertTrue(result.medianPnl() < expectedPnl * 1.5);
    }

    @Test
    void run_emptyTrades_returnsEmpty() {
        BacktestResult baseline = createBaselineResult(0, 10000.0);
        MonteCarloSimulation mc = new MonteCarloSimulation(baseline, 100);
        MonteCarloSimulation.Result result = mc.run();

        assertEquals(100, result.totalRuns());
        assertTrue(result.pnlValuesSorted().isEmpty());
        assertEquals(0.0, result.meanPnl());
        assertEquals(0.0, result.medianPnl());
    }

    @Test
    void run_probabilityOfLoss_allPositiveTrades_zero() {
        BacktestResult baseline = createAllPositiveResult(20, 10000.0);
        MonteCarloSimulation mc = new MonteCarloSimulation(baseline, 200);
        MonteCarloSimulation.Result result = mc.run();

        // All trades are positive → loss probability should be 0%
        assertEquals(0.0, result.probabilityOfLoss(), 0.01);
    }

    @Test
    void percentile_knownValues() {
        List<Double> sorted = List.of(10.0, 20.0, 30.0, 40.0, 50.0);

        assertEquals(10.0, MonteCarloSimulation.Result.percentile(sorted, 0.0), 1e-10);
        assertEquals(50.0, MonteCarloSimulation.Result.percentile(sorted, 1.0), 1e-10);
        assertEquals(30.0, MonteCarloSimulation.Result.percentile(sorted, 0.50), 1e-10);
        // 25th percentile: pos = 1.0, value = 20 + 0*(30-20) = 20
        assertEquals(20.0, MonteCarloSimulation.Result.percentile(sorted, 0.25), 1e-10);
    }

    @Test
    void percentile_emptyList_returnsZero() {
        assertEquals(0.0, MonteCarloSimulation.Result.percentile(List.of(), 0.5));
    }

    @Test
    void run_summary_doesNotThrow() {
        BacktestResult baseline = createBaselineResult(30, 10000.0);
        MonteCarloSimulation mc = new MonteCarloSimulation(baseline, 50);
        MonteCarloSimulation.Result result = mc.run();

        assertDoesNotThrow(result::printSummary);
    }

    @Test
    void run_bestPnl_worstPnl_ordering() {
        BacktestResult baseline = createBaselineResult(40, 10000.0);
        MonteCarloSimulation mc = new MonteCarloSimulation(baseline, 200);
        MonteCarloSimulation.Result result = mc.run();

        assertTrue(result.worstPnl() <= result.var95());
        assertTrue(result.var95() <= result.medianPnl());
        assertTrue(result.medianPnl() <= result.bestPnl());
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static BacktestResult createBaselineResult(int tradeCount, double initialCapital) {
        return createBaselineResult(tradeCount, initialCapital, 10.0);
    }

    private static BacktestResult createBaselineResult(int tradeCount, double initialCapital,
                                                        double avgPnl) {
        List<Trade> trades = new ArrayList<>(tradeCount);
        double totalPnl = 0;
        for (int i = 0; i < tradeCount; i++) {
            double pnl = avgPnl * (0.5 + Math.random());
            trades.add(new Trade("EURUSD", Order.Side.BUY, 1.1000, 1.1000 + pnl / 10000,
                10000, Instant.now(), Instant.now()));
            totalPnl += pnl;
        }
        double finalEquity = initialCapital + totalPnl;
        List<Double> equityCurve = List.of(initialCapital, finalEquity);

        return BacktestResult.builder()
            .strategyName("Test")
            .initialCapital(initialCapital)
            .finalEquity(finalEquity)
            .totalPnl(totalPnl)
            .totalTrades(tradeCount)
            .equityCurve(equityCurve)
            .trades(trades)
            .periodStart(Instant.parse("2026-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2026-06-01T00:00:00Z"))
            .build();
    }

    private static BacktestResult createAllPositiveResult(int tradeCount, double initialCapital) {
        List<Trade> trades = new ArrayList<>(tradeCount);
        double totalPnl = 0;
        for (int i = 0; i < tradeCount; i++) {
            double pnl = 10.0 + Math.random() * 5;
            trades.add(new Trade("EURUSD", Order.Side.BUY, 1.1000, 1.1050,
                10000, Instant.now(), Instant.now()));
            totalPnl += pnl;
        }
        double finalEquity = initialCapital + totalPnl;
        List<Double> equityCurve = List.of(initialCapital, finalEquity);

        return BacktestResult.builder()
            .strategyName("AllPos")
            .initialCapital(initialCapital)
            .finalEquity(finalEquity)
            .totalPnl(totalPnl)
            .totalTrades(tradeCount)
            .equityCurve(equityCurve)
            .trades(trades)
            .periodStart(Instant.parse("2026-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2026-06-01T00:00:00Z"))
            .build();
    }
}
