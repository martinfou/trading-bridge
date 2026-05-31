package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventJson;
import com.martinfou.trading.backtest.events.RunEventType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteEventStoreTest {

    @Test
    void eventsSurviveCloseAndReopen(@TempDir Path tempDir) {
        Path db = tempDir.resolve("events.db");
        EventStoreConfig config = EventStoreConfig.withDbPath(db);

        long seq1;
        long seq2;
        try (EventStore store = EventStores.sqlite(config)) {
            seq1 = store.append("run-a", sampleEvent("run-a", RunEventType.RUN_STARTED));
            seq2 = store.append("run-a", sampleEvent("run-a", RunEventType.RUN_ENDED));
            assertEquals(2, store.count("run-a"));
        }

        try (EventStore store = EventStores.sqlite(config)) {
            assertEquals(2, store.count("run-a"));
            List<RunEvent> replay = store.replayAll("run-a");
            assertEquals(2, replay.size());
            assertEquals(RunEventType.RUN_STARTED, replay.get(0).type());
            assertEquals(RunEventType.RUN_ENDED, replay.get(1).type());
            assertEquals(1, store.query("run-a", 0, 1).size());
            assertEquals(1, store.query("run-a", seq1, 10).size());
            assertEquals(0, store.query("run-a", seq2, 10).size());
        }
    }

    @Test
    void storesExactJsonLine(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("events.db");
        RunEvent original = sampleEvent("run-json", RunEventType.RUN_STARTED);
        String expectedJson = RunEventJson.toJsonLine(original);

        try (EventStore store = EventStores.sqlite(EventStoreConfig.withDbPath(db))) {
            store.append("run-json", original);
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT json_line FROM events")) {
            assertTrue(rs.next());
            assertEquals(expectedJson, rs.getString("json_line"));
        }

        try (EventStore store = EventStores.sqlite(EventStoreConfig.withDbPath(db))) {
            RunEvent parsed = store.replayAll("run-json").getFirst();
            assertEquals(original.type(), parsed.type());
            assertEquals(original.runId(), parsed.runId());
            assertEquals(original.strategyId(), parsed.strategyId());
            assertEquals(original.symbol(), parsed.symbol());
            assertEquals(original.mode(), parsed.mode());
            assertEquals(100, ((Number) parsed.payload().get("barCount")).intValue());
        }
    }

    @Test
    void defaults_usesRepoDataRuntimeWhenAvailable() {
        Path repoRoot = EventStoreConfig.findRepoRoot();
        if (repoRoot == null) {
            return;
        }
        EventStoreConfig config = EventStoreConfig.defaults();
        assertEquals(
            RuntimeDataPaths.defaultEventStorePath(),
            config.dbPath());
    }

    @Test
    void withDbPath_createsParentDirectories(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("nested/runtime/events.db");
        EventStoreConfig config = EventStoreConfig.withDbPath(db);
        config.ensureParentDirectories();

        try (EventStore store = EventStores.sqlite(config)) {
            store.append("run-a", sampleEvent("run-a", RunEventType.RUN_STARTED));
        }

        assertNotNull(db);
        assertTrue(java.nio.file.Files.isRegularFile(db));
    }

    private static RunEvent sampleEvent(String runId, RunEventType type) {
        return new RunEvent(
            RunEvent.SCHEMA_VERSION,
            type,
            Instant.parse("2026-05-23T12:00:00Z"),
            runId,
            "LondonOpenRangeBreakout",
            "EUR_USD",
            RunMode.BACKTEST.name(),
            Map.of("barCount", 100));
    }
}
