package com.martinfou.trading.backtest.persistence;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BacktestPersistenceServiceTest {

    @TempDir
    Path tempDir;

    private Path dbFile;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("test_persistence.db");
        System.setProperty("TRADING_BRIDGE_EVENT_STORE", dbFile.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("TRADING_BRIDGE_EVENT_STORE");
    }

    @Test
    void testParameterHashingIsDeterministic() {
        Map<String, Object> params1 = new HashMap<>();
        params1.put("slowPeriod", 50);
        params1.put("fastPeriod", 20);

        Map<String, Object> params2 = new HashMap<>();
        params2.put("fastPeriod", 20);
        params2.put("slowPeriod", 50);

        String hash1 = BacktestPersistenceService.computeParameterHash(params1);
        String hash2 = BacktestPersistenceService.computeParameterHash(params2);

        assertEquals(hash1, hash2);

        // Different params should yield different hash
        params2.put("slowPeriod", 51);
        String hash3 = BacktestPersistenceService.computeParameterHash(params2);
        assertNotEquals(hash1, hash3);
    }

    @Test
    void testParameterExtractionFromCodedStrategy() {
        Strategy strategy = new DummyCodedStrategy("TestSymbol", 15, 30, true);
        Map<String, Object> params = BacktestPersistenceService.extractParameters(strategy);

        assertEquals("TestSymbol", params.get("symbol"));
        assertEquals(15, params.get("fastPeriod"));
        assertEquals(30, params.get("slowPeriod"));
        assertEquals(true, params.get("enabled"));

        // Ignore internal state fields like list/log
        assertFalse(params.containsKey("history"));
        assertFalse(params.containsKey("orders"));
    }

    @Test
    void testSaveResultAndRead() throws IOException {
        Strategy strategy = new DummyCodedStrategy("EUR_USD", 10, 20, false);
        RunContext context = RunContext.forStrategy(
            "run_999",
            "DummyStrategy",
            strategy,
            "EUR_USD",
            RunMode.BACKTEST,
            List.of(),
            10000.0,
            null
        );

        BacktestResult result = BacktestResult.builder()
            .strategyName("DummyStrategy")
            .initialCapital(10000.0)
            .finalEquity(10500.0)
            .totalPnl(500.0)
            .totalReturnPct(5.0)
            .totalTrades(10)
            .winningTrades(6)
            .losingTrades(4)
            .winRatePct(60.0)
            .maxDrawdownPct(2.5)
            .sharpeRatio(1.5)
            .profitFactor(1.3)
            .equityCurve(List.of(10000.0, 10200.0, 10500.0))
            .periodStart(Instant.now().minusSeconds(3600))
            .periodEnd(Instant.now())
            .build();

        BacktestPersistenceService.save("run_999", context, result);

        // Verify that the run is persisted in the db file
        try (SqliteBacktestRunStore store = new SqliteBacktestRunStore(dbFile)) {
            assertTrue(store.get("run_999").isPresent());
            BacktestRunDetails details = store.get("run_999").get();
            assertEquals("EUR_USD", details.symbol());
            assertEquals("DummyStrategy", details.strategyId());
            assertEquals(1.5, details.sharpeRatio(), 0.0001);
            assertEquals(1.3, details.profitFactor(), 0.0001);
            assertTrue(details.parameters().contains("fastPeriod"));
            assertTrue(details.parameters().contains("slowPeriod"));
            assertEquals("[10000.0,10200.0,10500.0]", details.equityCurve());
        }
    }

    private static class DummyCodedStrategy implements Strategy {
        private final String symbol;
        private final int fastPeriod;
        private final int slowPeriod;
        private final boolean enabled;
        
        // fields to ignore
        private final List<Bar> history = new ArrayList<>();
        private final List<Order> orders = new ArrayList<>();

        public DummyCodedStrategy(String symbol, int fastPeriod, int slowPeriod, boolean enabled) {
            this.symbol = symbol;
            this.fastPeriod = fastPeriod;
            this.slowPeriod = slowPeriod;
            this.enabled = enabled;
        }

        @Override public String name() { return "Dummy"; }
        @Override public void onBar(Bar bar) {}
        @Override public void onTick(double bid, double ask, long volume) {}
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() {}
    }
}
