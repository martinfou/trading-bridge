package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRecordStoreTest {

    @Test
    void testInMemoryStore() {
        try (RunRecordStore store = new InMemoryRunRecordStore()) {
            RunConfigSnapshot snapshot = new RunConfigSnapshot(
                "SmaCrossover", "EUR_USD", "LIVE", "sample", 100, null, 1000.0, null, null, "LIVE_OANDA"
            );
            RunRecord record = new RunRecord(
                "run-123", "SmaCrossover", "EUR_USD", RunMode.LIVE, snapshot
            );
            record.incrementRestartCount(Instant.parse("2026-06-28T10:00:00Z"));
            store.save(record);

            RunRecord loaded = store.get("run-123").orElseThrow();
            assertEquals("run-123", loaded.runId());
            assertEquals("SmaCrossover", loaded.strategyId());
            assertEquals(1, loaded.restartCount());
            assertEquals(Instant.parse("2026-06-28T10:00:00Z"), loaded.lastRestartAt().orElseThrow());

            assertEquals(1, store.listAll().size());
            store.delete("run-123");
            assertTrue(store.get("run-123").isEmpty());
        }
    }

    @Test
    void testSqliteStorePersistence(@TempDir Path tempDir) {
        EventStoreConfig config = EventStoreConfig.withDbPath(tempDir.resolve("test_runs.db"));
        try (RunRecordStore store = new SqliteRunRecordStore(config)) {
            RunConfigSnapshot snapshot = new RunConfigSnapshot(
                "SmaCrossover", "EUR_USD", "LIVE", "sample", 100, null, 1000.0, null, null, "LIVE_OANDA"
            );
            RunRecord record = new RunRecord(
                "run-456", "SmaCrossover", "EUR_USD", RunMode.LIVE, snapshot
            );
            record.markRunning();
            record.incrementRestartCount(Instant.parse("2026-06-28T12:00:00Z"));
            store.save(record);
        }

        try (RunRecordStore store = new SqliteRunRecordStore(config)) {
            RunRecord loaded = store.get("run-456").orElseThrow();
            assertEquals("run-456", loaded.runId());
            assertEquals("SmaCrossover", loaded.strategyId());
            assertEquals(RunRecord.Status.RUNNING, loaded.status());
            assertEquals(1, loaded.restartCount());
            assertEquals(Instant.parse("2026-06-28T12:00:00Z"), loaded.lastRestartAt().orElseThrow());
            assertEquals(1, store.listAll().size());

            // Update record
            loaded.markCompleted(Map.of("pnl", 50.0));
            loaded.resetRestartCount();
            store.save(loaded);
        }

        try (RunRecordStore store = new SqliteRunRecordStore(config)) {
            RunRecord loaded = store.get("run-456").orElseThrow();
            assertEquals(RunRecord.Status.COMPLETED, loaded.status());
            assertEquals(0, loaded.restartCount());
            assertTrue(loaded.lastRestartAt().isEmpty());
            assertEquals(50.0, loaded.endedPayload().orElseThrow().get("pnl"));
        }
    }
}
