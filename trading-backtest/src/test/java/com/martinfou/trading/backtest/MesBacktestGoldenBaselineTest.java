package com.martinfou.trading.backtest;

import com.martinfou.trading.core.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MesBacktestGoldenBaselineTest {

    static class MesTrendPullbackStrategy implements Strategy {
        private final List<Order> pending = new ArrayList<>();
        private double lastEma = 0.0;
        private int barCount = 0;

        @Override
        public String name() {
            return "MesTrendPullbackStrategy";
        }

        @Override
        public void onBar(Bar bar) {
            barCount++;
            if (barCount == 1) {
                lastEma = bar.close();
                return;
            }
            double alpha = 2.0 / (10 + 1);
            lastEma = alpha * bar.close() + (1.0 - alpha) * lastEma;

            // When price pulls back to EMA in an uptrend, place BUY LIMIT order
            if (bar.close() > lastEma && bar.low() <= lastEma + 2.0) {
                double limitPrice = FuturesRegistry.quantizePrice("MES", lastEma);
                double sl = FuturesRegistry.quantizePrice("MES", limitPrice - 8.0);
                double tp = FuturesRegistry.quantizePrice("MES", limitPrice + 16.0);
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.LIMIT, 1.0, limitPrice, sl, tp));
            }
        }

        @Override
        public void onTick(double bid, double ask, long volume) {}

        @Override
        public List<Order> getPendingOrders() {
            List<Order> copy = new ArrayList<>(pending);
            pending.clear();
            return copy;
        }

        @Override
        public void reset() {
            pending.clear();
            lastEma = 0.0;
            barCount = 0;
        }
    }

    @Test
    void testEndToEndMesFuturesBacktestSimulation() {
        // Generate a 10-day synthetic hourly bar series for MES across RTH and ETH
        List<Bar> bars = new ArrayList<>();
        ZonedDateTime start = ZonedDateTime.of(2024, 3, 3, 18, 0, 0, 0, ZoneId.of("America/New_York")); // Sunday Globex Open

        double currentPrice = 5000.00;
        for (int i = 0; i < 200; i++) {
            ZonedDateTime barTime = start.plusHours(i);
            Instant timestamp = barTime.toInstant();

            // Simulate market oscillations
            double wave = Math.sin(i * 0.15) * 15.0 + (i * 0.25);
            double base = 5000.00 + wave;
            double open = FuturesRegistry.quantizePrice("MES", base);
            double high = FuturesRegistry.quantizePrice("MES", open + 4.50);
            double low = FuturesRegistry.quantizePrice("MES", open - 3.75);
            double close = FuturesRegistry.quantizePrice("MES", open + 1.25);

            bars.add(new Bar("MES", timestamp, open, high, low, close, 500));
        }

        MarginTracker marginTracker = new MarginTracker(0.05, true, 25_000.0);
        BacktestEngine engine = new BacktestEngine(new MesTrendPullbackStrategy(), bars, 50_000.0)
            .withFillMode(FillMode.TRADE_THROUGH)
            .withCommissionFixed(0.62)
            .withSlippagePct(0.00005) // 0.25 pt (1 tick) on MES
            .withStopSlippagePct(0.0001)
            .withRollover(true)
            .withMarginTracker(marginTracker);

        BacktestResult result = engine.run();

        assertNotNull(result);
        assertEquals("MesTrendPullbackStrategy", result.strategyName());
        assertEquals(50_000.0, result.initialCapital(), 1e-6);

        // Verify non-zero trades and valid performance calculations
        assertTrue(result.totalTrades() > 0, "Strategy should produce trades over 200 bars");
        assertTrue(result.totalCommission() > 0, "Commissions must be collected on trades");
        assertTrue(result.totalSlippage() > 0, "Slippage must be tracked");
        assertNotNull(result.equityCurve());
        assertFalse(result.equityCurve().isEmpty());

        // Verify Margin tracker remains healthy and free of PDT restriction on MES
        assertEquals(MarginTracker.MarginHealth.HEALTHY, marginTracker.currentHealth());

        // Verify all executed trades have valid tick quantization on exit prices
        for (Trade trade : result.trades()) {
            FuturesContract mes = FuturesRegistry.find("MES").orElseThrow();
            assertTrue(mes.isValidTick(trade.exitPrice()), "Exit price must be valid tick: " + trade.exitPrice());
            assertNotNull(trade.id());
        }
    }
}

