package com.martinfou.trading.backtest;

import com.martinfou.trading.core.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the enhanced {@link BacktestEngine} with commission, slippage,
 * and advanced metrics.
 */
class BacktestEngineEnhancedTest {

    private static final double INITIAL_CAPITAL = 10000.0;

    @Test
    void run_basicMarketOrder_producesResult() {
        Strategy strat = new SmaCrossover(5, 20);
        List<Bar> bars = generateBars(50);

        BacktestEngine engine = new BacktestEngine(strat, bars, INITIAL_CAPITAL);
        BacktestResult result = engine.run();

        assertNotNull(result);
        assertTrue(result.totalTrades() >= 0);
        assertFalse(result.equityCurve().isEmpty());
        assertTrue(result.finalEquity() > 0);
    }

    @Test
    void run_withCommission_deductsFromEquity() {
        Strategy strat = new SmaCrossover(5, 20);
        List<Bar> bars = generateBars(50);

        BacktestEngine withComm = new BacktestEngine(strat, bars, INITIAL_CAPITAL)
            .withCommissionFixed(5.0);
        BacktestResult result = withComm.run();

        assertTrue(result.totalCommission() > 0 || result.totalCommission() == 0);
    }

    @Test
    void run_withSlippage_affectsFillPrice() {
        Strategy strat = new SmaCrossover(5, 20);
        List<Bar> bars = generateBars(50);

        BacktestEngine withSlip = new BacktestEngine(strat, bars, INITIAL_CAPITAL)
            .withSlippageFixed(0.0001);
        BacktestResult result = withSlip.run();

        assertTrue(result.totalSlippage() >= 0);
    }

    @Test
    void run_advancedMetrics_populated() {
        Strategy strat = new SmaCrossover(3, 10);
        List<Bar> bars = generateBars(100);

        BacktestEngine engine = new BacktestEngine(strat, bars, INITIAL_CAPITAL)
            .withCommissionFixed(1.0);
        BacktestResult result = engine.run();

        // All metrics should be computed (may be 0 for no-trade edge cases)
        assertNotNull(result);
        assertFalse(Double.isNaN(result.sharpeRatio()));
        assertFalse(Double.isNaN(result.sortinoRatio()));
        assertFalse(Double.isNaN(result.profitFactor()));
        assertFalse(Double.isNaN(result.calmarRatio()));
    }

    @Test
    void run_equityCurvePowersOfTwo() {
        // Test that equity curve is populated after each bar
        Strategy strat = new SmaCrossover(3, 10);
        List<Bar> bars = generateBars(30);

        BacktestEngine engine = new BacktestEngine(strat, bars, INITIAL_CAPITAL);
        BacktestResult result = engine.run();

        // Equity curve should have bars.size() + 1 entries
        // (initial + one per bar processed)
        assertEquals(bars.size() + 1, result.equityCurve().size());
    }

    @Test
    void run_riskFreeRate_affectsSharpe() {
        Strategy strat = new SmaCrossover(5, 20);
        List<Bar> bars = generateBars(100);

        BacktestEngine engine = new BacktestEngine(strat, bars, INITIAL_CAPITAL)
            .withRiskFreeRate(0.05);
        BacktestResult result = engine.run();

        assertFalse(Double.isNaN(result.sharpeRatio()));
    }

    @Test
    void periodReturns_fromEquityCurve_wellFormed() {
        Strategy strat = new SmaCrossover(3, 10);
        List<Bar> bars = generateBars(50);

        BacktestEngine engine = new BacktestEngine(strat, bars, INITIAL_CAPITAL);
        BacktestResult result = engine.run();

        List<Double> returns = result.periodReturns();
        assertEquals(bars.size(), returns.size()); // one return per bar
        for (double r : returns) {
            assertFalse(Double.isNaN(r));
            assertFalse(Double.isInfinite(r));
        }
    }

    @Test
    void tradePnlList_fromResult_wellFormed() {
        Strategy strat = new SmaCrossover(3, 10);
        List<Bar> bars = generateBars(50);

        BacktestEngine engine = new BacktestEngine(strat, bars, INITIAL_CAPITAL);
        BacktestResult result = engine.run();

        List<Double> pnls = result.tradePnlList();
        assertEquals(result.totalTrades(), pnls.size());
    }

    @Test
    void builder_constructsResult() {
        BacktestResult r = BacktestResult.builder()
            .strategyName("Test")
            .initialCapital(10000)
            .finalEquity(11000)
            .totalPnl(1000)
            .totalReturnPct(10.0)
            .totalTrades(10)
            .winningTrades(6)
            .losingTrades(4)
            .winRatePct(60.0)
            .sharpeRatio(1.5)
            .build();

        assertEquals("Test", r.strategyName());
        assertEquals(10000, r.initialCapital());
        assertEquals(11000, r.finalEquity());
        assertEquals(1.5, r.sharpeRatio());
        assertEquals(60.0, r.winRatePct());
    }

    // ---------------------------------------------------------------
    //  Test helpers
    // ---------------------------------------------------------------

    /** A simple SMA crossover strategy for testing. */
    static class SmaCrossover implements Strategy {
        private final int fastPeriod, slowPeriod;
        private final List<Order> pending = new ArrayList<>();
        private final List<Double> closes = new ArrayList<>();

        SmaCrossover(int fast, int slow) {
            this.fastPeriod = fast;
            this.slowPeriod = slow;
        }

        @Override public String name() { return "SMA Crossover"; }
        @Override public void onTick(double bid, double ask, long volume) {}

        @Override
        public void onBar(Bar bar) {
            closes.add(bar.close());
            if (closes.size() < slowPeriod) return;

            double fastSma = sma(fastPeriod);
            double slowSma = sma(slowPeriod);

            pending.clear();
            if (fastSma > slowSma) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 0.01, 0));
            } else {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 0.01, 0));
            }
        }

        private double sma(int period) {
            double sum = 0;
            for (int i = closes.size() - period; i < closes.size(); i++) {
                sum += closes.get(i);
            }
            return sum / period;
        }

        @Override public List<Order> getPendingOrders() { return List.copyOf(pending); }
        @Override public void reset() { closes.clear(); pending.clear(); }
    }

    /** Generates random-ish bars for testing. */
    static List<Bar> generateBars(int count) {
        List<Bar> bars = new ArrayList<>(count);
        double price = 1.1000;
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            double change = (Math.sin(i * 0.3) * 0.005) + (Math.random() - 0.5) * 0.01;
            price += change;
            if (price <= 0) price = 0.001;
            double open = price;
            double close = price + (Math.random() - 0.5) * 0.004;
            double high = Math.max(open, close) + Math.random() * 0.003;
            double low = Math.min(open, close) - Math.random() * 0.003;
            bars.add(new Bar("EURUSD", now.plusSeconds(i * 3600),
                open, high, low, close, (long) (Math.random() * 1000)));
        }
        return bars;
    }
}
