package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

// Alias for brevity
import com.martinfou.trading.backtest.WalkForwardOptimizer.WindowResult;

/**
 * Tests for {@link WalkForwardOptimizer}.
 */
class WalkForwardOptimizerTest {

    @Test
    void run_withMultipleYears_producesWindows() {
        List<Bar> bars = generateDailyBars(500);
        BiFunction<List<Bar>, List<Bar>, BacktestResult> builder = (isBars, oosBars) ->
            BacktestResult.builder()
                .strategyName("Test")
                .initialCapital(10000)
                .finalEquity(10000 + oosBars.size())
                .totalPnl(oosBars.size())
                .totalReturnPct(oosBars.size() / 100.0)
                .totalTrades(oosBars.size())
                .sharpeRatio(0.5)
                .maxDrawdownPct(5.0)
                .equityCurve(List.of(10000.0, 10000.0 + oosBars.size()))
                .trades(List.of())
                .periodStart(isBars.getFirst().timestamp())
                .periodEnd(oosBars.getLast().timestamp())
                .build();

        WalkForwardOptimizer wfo = new WalkForwardOptimizer(
            builder, bars, 100, 30, 30);
        WalkForwardOptimizer.Result result = wfo.run();

        assertNotNull(result);
        assertTrue(result.windowCount() > 0);
        assertTrue(result.avgOosReturnPct() > 0);
    }

    @Test
    void run_insufficientData_returnsEmpty() {
        List<Bar> bars = generateDailyBars(20);
        BiFunction<List<Bar>, List<Bar>, BacktestResult> builder = (isBars, oosBars) ->
            BacktestResult.builder().build();

        WalkForwardOptimizer wfo = new WalkForwardOptimizer(
            builder, bars, 100, 30);
        WalkForwardOptimizer.Result result = wfo.run();

        assertEquals(0, result.windowCount());
    }

    @Test
    void isRobust_goodMetrics_returnsTrue() {
        List<WindowResult> windows = List.of(
            new WindowResult(0, 10, 8, 1.5, 1.2, 5, 6, 20, 18, 100, 30),
            new WindowResult(1, 12, 9, 1.8, 1.3, 4, 7, 22, 20, 100, 30),
            new WindowResult(2, 8, 7, 1.2, 1.0, 6, 5, 18, 15, 100, 30)
        );
        WalkForwardOptimizer.Result result = new WalkForwardOptimizer.Result(windows, 100, 30);
        assertTrue(result.isRobust());
    }

    @Test
    void isRobust_poorMetrics_returnsFalse() {
        List<WindowResult> windows = List.of(
            new WindowResult(0, 15, -5, 2.0, -0.3, 3, 15, 25, 12, 100, 30),
            new WindowResult(1, 18, -3, 2.5, -0.2, 2, 12, 28, 10, 100, 30)
        );
        WalkForwardOptimizer.Result result = new WalkForwardOptimizer.Result(windows, 100, 30);
        assertFalse(result.isRobust());
    }

    @Test
    void walkForwardEfficiency_knownValues() {
        List<WindowResult> windows = List.of(
            new WindowResult(0, 10, 8, 1.0, 0.7, 5, 6, 20, 18, 100, 30)
        );
        WalkForwardOptimizer.Result result = new WalkForwardOptimizer.Result(windows, 100, 30);
        assertEquals(0.7, result.walkForwardEfficiency(), 1e-10);
    }

    @Test
    void oosWinRate_allPositive_returns100() {
        List<WindowResult> windows = List.of(
            new WindowResult(0, 10, 5, 1.0, 0.5, 5, 6, 20, 18, 100, 30),
            new WindowResult(1, 12, 3, 1.2, 0.4, 4, 7, 22, 20, 100, 30)
        );
        WalkForwardOptimizer.Result result = new WalkForwardOptimizer.Result(windows, 100, 30);
        assertEquals(100.0, result.oosWinRate(), 1e-10);
    }

    @Test
    void summary_doesNotThrow() {
        List<WindowResult> windows = List.of(
            new WindowResult(0, 10, 5, 1.0, 0.5, 5, 6, 20, 18, 100, 30)
        );
        WalkForwardOptimizer.Result result = new WalkForwardOptimizer.Result(windows, 100, 30);
        assertDoesNotThrow(result::printSummary);
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static List<Bar> generateDailyBars(int count) {
        List<Bar> bars = new ArrayList<>(count);
        double price = 1.1000;
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            price += (Math.random() - 0.5) * 0.005;
            bars.add(new Bar("EURUSD", start.plusSeconds(i * 86400L),
                price, price + 0.001, price - 0.001, price + (Math.random() - 0.5) * 0.002,
                1000));
        }
        return bars;
    }
}
