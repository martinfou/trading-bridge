package com.martinfou.trading.backtest.wfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.core.strategy.ParameterRange;
import com.martinfou.trading.data.HistoricalDataLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import java.time.Instant;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WfaEngineTest {

    static Path getBarsDir() {
        Path path = Path.of("data/historical/bars");
        if (!Files.isDirectory(path)) {
            path = Path.of("../data/historical/bars");
        }
        return path;
    }

    static boolean hasEurUsd2012() {
        return Files.isRegularFile(getBarsDir().resolve("EUR_USD_H1_2012.bars"));
    }

    @Test
    @EnabledIf("hasEurUsd2012")
    void testWalkForwardAnalysisExecution() throws Exception {
        // Load real EUR_USD 2012 H1 bars
        List<Bar> bars = HistoricalDataLoader.loadYearSpec(
            "EUR_USD", "2012", "H1", getBarsDir());

        assertFalse(bars.isEmpty(), "Bars list should not be empty");

        // Define search ranges for parameters
        List<ParameterRange> ranges = List.of(
            new ParameterRange("fastPeriod", 5.0, 15.0, 5.0),  // 5, 10, 15
            new ParameterRange("slowPeriod", 20.0, 30.0, 10.0) // 20, 30
        );

        // Configure Walk-Forward Analysis
        WfaConfig config = new WfaConfig(
            "EUR_USD",
            100_000.0,
            180, // In-Sample days (approx 6 months)
            60,  // Out-of-Sample days (approx 2 months)
            false, // Sliding window (not anchored)
            ranges
        );

        // Instantiate WfaEngine using TestStrategy
        try (WfaEngine engine = new WfaEngine(config, TestStrategy::new, bars)) {
            // Run engine
            WfaReport report = engine.execute();

            // Perform assertions on WfaReport
            assertNotNull(report, "Report should not be null");
            assertNotNull(report.wfaId(), "WFA ID should not be null");
            assertEquals("EUR_USD", report.instrument());
            assertEquals("TestStrategy", report.strategyName());
            assertFalse(report.folds().isEmpty(), "Folds list should not be empty");

            // Print summary statistics
            System.out.println("WFA Execution Successful!");
            System.out.println("WFA ID: " + report.wfaId());
            System.out.println("Global OOS Sharpe: " + report.oosSharpe());
            System.out.println("Global OOS Return: " + report.oosReturnPct() + "%");
            System.out.println("WFE: " + report.wfe());
            System.out.println("Total OOS Trades: " + report.oosTradesCount());

            // Check that folds are contiguous and ordered
            for (int i = 0; i < report.folds().size(); i++) {
                WfaFoldResult fold = report.folds().get(i);
                assertEquals(i, fold.index());
                assertNotNull(fold.chosenParameters());
                assertTrue(fold.isTradesCount() >= 0);
                assertTrue(fold.oosTradesCount() >= 0);
            }

            // Serialize report to JSON file target/wfa-test.json
            ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
            File outputFile = new File("target/wfa-test.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, report);

            assertTrue(outputFile.exists(), "Output JSON file should be written");
            assertTrue(outputFile.length() > 0, "Output JSON file should not be empty");
        }
    }

    /**
     * Dummy strategy used to generate some trades for testing.
     */
    static class TestStrategy implements Strategy {
        private final String name = "TestStrategy";
        private int fastPeriod = 10;
        private int slowPeriod = 20;
        private final List<Bar> history = new ArrayList<>();
        private final List<Order> pending = new ArrayList<>();
        private boolean inPosition = false;

        @Override
        public String name() {
            return name;
        }

        @Override
        public void onBar(Bar bar) {
            history.add(bar);
            int idx = history.size();
            if (idx % fastPeriod == 0 && !inPosition) {
                pending.add(new Order(bar.symbol(), Order.Side.BUY, Order.Type.MARKET, 10000, bar.close()));
                inPosition = true;
            } else if (inPosition && idx % slowPeriod == 0) {
                pending.add(new Order(bar.symbol(), Order.Side.SELL, Order.Type.MARKET, 10000, bar.close()).closeOnly());
                inPosition = false;
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
            history.clear();
            pending.clear();
            inPosition = false;
        }
    }

    static class FixedQuantityStrategy implements Strategy {
        private final Strategy delegate;
        private final double quantity;

        public FixedQuantityStrategy(Strategy delegate, double quantity) {
            this.delegate = delegate;
            this.quantity = quantity;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public void onBar(Bar bar) {
            delegate.onBar(bar);
        }

        @Override
        public void onTick(double bid, double ask, long volume) {
            delegate.onTick(bid, ask, volume);
        }

        @Override
        public List<Order> getPendingOrders() {
            return delegate.getPendingOrders();
        }

        @Override
        public void reset() {
            delegate.reset();
        }
    }

    @Test
    @EnabledIf("hasEurUsd2012")
    void testWalkForwardAnalysisWithFixedQuantityStrategy() throws Exception {
        List<Bar> bars = HistoricalDataLoader.loadYearSpec("EUR_USD", "2012", "H1", getBarsDir());
        assertFalse(bars.isEmpty());

        List<ParameterRange> ranges = List.of(
            new ParameterRange("fastPeriod", 10.0, 10.0, 1.0),
            new ParameterRange("slowPeriod", 20.0, 20.0, 1.0)
        );

        WfaConfig config = new WfaConfig(
            "EUR_USD",
            100_000.0,
            180,
            60,
            false,
            ranges
        );

        java.util.function.Supplier<Strategy> supplier = () -> new FixedQuantityStrategy(new TestStrategy(), 5000.0);

        try (WfaEngine engine = new WfaEngine(config, supplier, bars)) {
            WfaReport report = engine.execute();
            assertNotNull(report);
            assertFalse(report.folds().isEmpty());

            for (WfaFoldResult fold : report.folds()) {
                assertEquals(10.0, fold.chosenParameters().get("fastPeriod"));
                assertEquals(20.0, fold.chosenParameters().get("slowPeriod"));
            }
        }
    }

    @Test
    @EnabledIf("hasEurUsd2012")
    void testWalkForwardAnalysisAnchored() throws Exception {
        List<Bar> bars = HistoricalDataLoader.loadYearSpec("EUR_USD", "2012", "H1", getBarsDir());
        assertFalse(bars.isEmpty());

        List<ParameterRange> ranges = List.of(
            new ParameterRange("fastPeriod", 10.0, 10.0, 1.0),
            new ParameterRange("slowPeriod", 20.0, 20.0, 1.0)
        );

        WfaConfig config = new WfaConfig(
            "EUR_USD",
            100_000.0,
            180,
            60,
            true, // anchored
            ranges
        );

        try (WfaEngine engine = new WfaEngine(config, TestStrategy::new, bars)) {
            WfaReport report = engine.execute();
            assertNotNull(report);
            assertTrue(report.anchored());
            assertFalse(report.folds().isEmpty());

            // Check that the In-Sample start date is always the first bar timestamp (anchored)
            String expectedStart = bars.getFirst().timestamp().toString();
            for (WfaFoldResult fold : report.folds()) {
                assertEquals(expectedStart, fold.isStart());
            }
        }
    }

    @Test
    void testSyncCrossingTradesToStrategyReflection() throws Exception {
        java.lang.reflect.Method method = WfaEngine.class.getDeclaredMethod("syncCrossingTradesToStrategy", 
            Strategy.class, List.class, Bar.class);
        method.setAccessible(true);

        Strategy mockStrategy = new Strategy() {
            public com.martinfou.trading.core.indicators.Indicators.TradeSide activeSide = null;
            public double activeSl = 0.0;
            public double activeTp = 0.0;
            public int barsInTrade = -1;
            @Override public String name() { return "Mock"; }
            @Override public void onBar(Bar bar) {}
            @Override public void onTick(double bid, double ask, long volume) {}
            @Override public List<Order> getPendingOrders() { return List.of(); }
            @Override public void reset() {}
        };

        List<Trade> crossingTrades = List.of(
            new Trade("EUR_USD", Order.Side.BUY, 1.0850, 0.0, 1000.0, 
                Instant.parse("2012-06-28T12:00:00Z"), Instant.parse("2012-06-29T12:00:00Z"), 
                1.0, 1.0750, 1.1050)
        );

        Bar bar = new Bar("EUR_USD", Instant.parse("2012-06-29T00:00:00Z"), 1.0850, 1.0860, 1.0840, 1.0850, 1000);

        method.invoke(null, mockStrategy, crossingTrades, bar);

        java.lang.reflect.Field sideField = mockStrategy.getClass().getDeclaredField("activeSide");
        sideField.setAccessible(true);
        assertNotNull(sideField.get(mockStrategy));
        assertEquals("LONG", sideField.get(mockStrategy).toString());

        java.lang.reflect.Field slField = mockStrategy.getClass().getDeclaredField("activeSl");
        slField.setAccessible(true);
        assertEquals(1.0750, slField.get(mockStrategy));

        java.lang.reflect.Field tpField = mockStrategy.getClass().getDeclaredField("activeTp");
        tpField.setAccessible(true);
        assertEquals(1.1050, tpField.get(mockStrategy));

        java.lang.reflect.Field barsField = mockStrategy.getClass().getDeclaredField("barsInTrade");
        barsField.setAccessible(true);
        assertEquals(0, barsField.get(mockStrategy));
    }
}
