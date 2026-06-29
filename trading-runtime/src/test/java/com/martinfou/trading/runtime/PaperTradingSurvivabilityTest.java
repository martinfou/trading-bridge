package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperTradingSurvivabilityTest {

    @Test
    void testPaperTradingSurvivability(@TempDir Path tempDir) throws Exception {
        EventStoreConfig config = EventStoreConfig.withDbPath(tempDir.resolve("survivability.db"));
        String runId = "survive-run-abc";

        // 1. Initial boot: Register a run, insert events and trades, simulate running state
        try (SqliteEventStore eventStore = new SqliteEventStore(config);
             RunManager manager = new RunManager(eventStore)) {

            RunConfigSnapshot snapshot = new RunConfigSnapshot(
                "LondonOpenRangeBreakout", "EUR_USD", "PAPER", "sample", 500, null, 10000.0, null, null, null
            );
            RunRecord record = manager.restoreRun(runId, snapshot);
            record.markRunning();
            manager.runRecordStore().save(record);

            // Append events
            eventStore.append(runId, new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.RUN_STARTED, Instant.now(), runId,
                "LondonOpenRangeBreakout", "EUR_USD", "PAPER", Map.of()
            ));
            eventStore.append(runId, new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.FILL, Instant.now(), runId,
                "LondonOpenRangeBreakout", "EUR_USD", "PAPER", Map.of("side", "BUY", "price", 1.1000)
            ));

            // Insert trade
            Trade trade = new Trade(
                "trade-survive-1", "EUR_USD", Order.Side.BUY, 1.1000, 1.1050, 1000.0,
                Instant.now(), Instant.now(), 5.0, 1.0950, 1.1100
            );
            manager.tradeStore().insert(runId, trade);

            // Verify initial state is correct
            assertEquals(1, manager.list(null).size());
            assertEquals(RunRecord.Status.RUNNING, record.status());
            assertEquals(1, manager.tradeStore().getTrades(runId).size());
            assertEquals(2, eventStore.count(runId));
        }

        // 2. Simulated JVM Kill has occurred: store is closed and JVM state is gone.
        // Re-open store and check that data survived and run was restored.
        try (SqliteEventStore eventStore = new SqliteEventStore(config);
             RunManager manager = new RunManager(eventStore)) {

            // Assert map in memory is empty initially before restore
            assertTrue(manager.list(null).isEmpty());

            // Reconstruct state from SQLite
            ControlPlaneMain.restoreActiveRuns(manager, config);

            // 3. Assertions
            // Assert the run was successfully restored and status is back to RUNNING
            RunRecord restoredRecord = manager.getRun(runId).orElseThrow();
            assertEquals(RunRecord.Status.RUNNING, restoredRecord.status());
            assertEquals("LondonOpenRangeBreakout", restoredRecord.strategyId());

            // Assert trade survived in the trade store
            List<Trade> restoredTrades = manager.tradeStore().getTrades(runId);
            assertEquals(1, restoredTrades.size());
            Trade restoredTrade = restoredTrades.getFirst();
            assertEquals("trade-survive-1", restoredTrade.id());
            assertEquals("EUR_USD", restoredTrade.symbol());
            assertEquals(1.1000, restoredTrade.entryPrice());
            assertEquals(1.1050, restoredTrade.exitPrice());

            // Assert events survived in event store
            assertEquals(2, eventStore.count(runId));
        }
    }
}
