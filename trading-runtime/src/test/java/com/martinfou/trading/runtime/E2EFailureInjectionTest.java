package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.exceptions.BrokerException;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.data.oanda.OandaStreamingClient;

class E2EFailureInjectionTest {

    @Test
    void testTransientNetworkDrop() {
        var store = new InMemoryEventStore();
        String runId = "test-transient-net";
        var config = new RunConfigSnapshot(
            "SampleStrategy", "EUR_USD", "LIVE", "sample", 500, null, 1000.0, 0.07, 1e-4, "LIVE_OANDA", "acct1"
        );

        Broker broker = mock(Broker.class);
        when(broker.getPositions()).thenThrow(new BrokerException("OANDA API 503 Service Unavailable"));

        var strategy = new Strategy() {
            @Override public String name() { return "stub"; }
            @Override public void onBar(com.martinfou.trading.core.Bar bar) {}
            @Override public void onTick(double bid, double ask, long time) {}
            @Override public List<Order> getPendingOrders() { return Collections.emptyList(); }
            @Override public void reset() {}
        };

        var killSwitchRegistry = new KillSwitchRegistry();
        
        var executor = new OandaStreamingExecutor(
            runId, null, config, strategy, broker, null, store, killSwitchRegistry, null,
            new RunRiskContext(new RiskEngine(), (run, cfg, m, check) -> {}, metrics -> {})
        );

        assertDoesNotThrow(() -> executor.reconnectBroker());
        assertFalse(killSwitchRegistry.isKilled("SampleStrategy"));
    }

    @Test
    void testKillSwitchIntegration() throws Exception {
        var store = new InMemoryEventStore();
        String runId = "test-killswitch-integration";
        var config = new RunConfigSnapshot(
            "SampleStrategy", "EUR_USD", "LIVE", "sample", 500, null, 1000.0, 0.07, 1e-4, "LIVE_OANDA", "acct1"
        );

        Broker broker = mock(Broker.class);
        
        var pendingOrder = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.10);
        var strategy = new Strategy() {
            @Override public String name() { return "SampleStrategy"; }
            @Override public void onBar(com.martinfou.trading.core.Bar bar) {}
            @Override public void onTick(double bid, double ask, long time) {}
            @Override public List<Order> getPendingOrders() { return List.of(pendingOrder); }
            @Override public void reset() {}
        };

        var killSwitchRegistry = new KillSwitchRegistry();
        
        var executor = new OandaStreamingExecutor(
            runId, null, config, strategy, broker, null, store, killSwitchRegistry, null,
            new RunRiskContext(new RiskEngine(), (run, cfg, m, check) -> {}, metrics -> {})
        );

        // Trip the kill switch
        killSwitchRegistry.kill("SampleStrategy");
        
        // Invoke executePendingOrders via reflection to reliably trigger the kill switch logic
        java.lang.reflect.Method method = OandaStreamingExecutor.class.getDeclaredMethod("executePendingOrders", com.martinfou.trading.core.Bar.class);
        method.setAccessible(true);
        method.invoke(executor, new com.martinfou.trading.core.Bar("EUR_USD", Instant.now(), 1.10, 1.10, 1.10, 1.10, 1));
        
        verify(broker, never()).submitOrder(any());

        List<RunEvent> events = store.replayAll(runId);
        boolean hasRejectEvent = events.stream().anyMatch(e -> e.type() == RunEventType.REJECT);
        assertTrue(hasRejectEvent, "Expected REJECT event due to KillSwitch activation");
    }

    @Test
    void testDatabaseLockingSQLITE_BUSY() throws Exception {
        var store = new InMemoryEventStore();
        var brokerFactory = BrokerFactory.fromRegistry(BrokerAccountRegistry.loadDefault());
        
        // Use the test hook constructor
        RunManager runManager = new RunManager(store, brokerFactory);
        
        // Create a mock RunRecordStore that throws an exception to simulate SQLITE_BUSY
        RunRecordStore mockRecordStore = mock(RunRecordStore.class);
        doThrow(new IllegalStateException("Failed to save run record", new java.sql.SQLException("SQLITE_BUSY")))
            .when(mockRecordStore).save(any(RunRecord.class));
            
        when(mockRecordStore.get(anyString())).thenReturn(Optional.empty());
        
        // Inject mock record store
        Field storeField = RunManager.class.getDeclaredField("runRecordStore");
        storeField.setAccessible(true);
        storeField.set(runManager, mockRecordStore);
        
        // Start a run so that we have something to stop. It should fail to persist but not crash RunManager.
        RunManager.StartRunRequest request = new RunManager.StartRunRequest(
            "LondonOpenRangeBreakout", "EUR_USD", "BACKTEST", new com.martinfou.trading.runtime.BarSourceResolver.BarsSource("sample", 500, (String) null, null),
            1000.0, 0.07, 1e-4, "BACKTEST"
        );

        // Attempting to start the run should catch the DB exception gracefully.
        // We will assert that it does not throw an exception and returns a valid runId.
        String runId = runManager.startRun(request);
        assertNotNull(runId, "Run should start successfully even if persistence fails");
        
        runManager.close();
    }
}
