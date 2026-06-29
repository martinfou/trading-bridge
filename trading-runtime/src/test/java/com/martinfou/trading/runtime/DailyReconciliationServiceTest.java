package com.martinfou.trading.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DailyReconciliationServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testDailyReconciliationReportGeneration(@TempDir Path tempDir) throws Exception {
        EventStoreConfig config = EventStoreConfig.withDbPath(tempDir.resolve("reconcile_db.db"));
        String runId = "reconcile-run-xyz";

        try (SqliteEventStore eventStore = new SqliteEventStore(config);
             RunManager manager = new RunManager(eventStore)) {

            RunConfigSnapshot snapshot = new RunConfigSnapshot(
                "LondonOpenRangeBreakout", "EUR_USD", "BACKTEST", "sample", 500, null, 10000.0, null, null, null
            );
            RunRecord record = manager.restoreRun(runId, snapshot);
            record.markCompleted(Map.of());
            manager.runRecordStore().save(record);

            // Mismatched fills and trades: 1 trade, 1 fill event instead of 2
            eventStore.append(runId, new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.FILL, Instant.now(), runId,
                "LondonOpenRangeBreakout", "EUR_USD", "BACKTEST", Map.of("side", "BUY", "price", 1.1000)
            ));

            Trade trade = new Trade(
                "trade-rec-1", "EUR_USD", Order.Side.BUY, 1.1000, 1.1050, 1000.0,
                Instant.now(), Instant.now(), 5.0, 1.0950, 1.1100
            );
            manager.tradeStore().insert(runId, trade);

            // Force data directory environment setup
            System.setProperty("user.home", tempDir.toString());

            // Instantiate service under test with fixed clock (say 11:30 PM)
            Instant fixedInstant = Instant.parse("2026-06-28T23:30:00Z");
            Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

            try (DailyReconciliationService service = new DailyReconciliationService(manager, fixedClock)) {
                Map<String, Object> report = service.runReconciliation();

                assertNotNull(report);
                assertEquals("MISMATCH", report.get("status"));
                assertEquals(1, report.get("totalRuns"));
                assertEquals(1, report.get("completedRuns"));
                assertEquals(0, report.get("activeRuns"));

                @SuppressWarnings("unchecked")
                List<String> warnings = (List<String>) report.get("warnings");
                assertEquals(1, warnings.size());
                assertTrue(warnings.getFirst().contains("FILL events (1) vs trades count (1)"));

                // Verify file exists
                Path reportPath = RuntimeDataPaths.defaultDataDirectory().resolve("reconciliation-report.json");
                assertTrue(Files.exists(reportPath));

                // Verify content of JSON report
                String content = Files.readString(reportPath);
                Map<?, ?> savedReport = mapper.readValue(content, Map.class);
                assertEquals("MISMATCH", savedReport.get("status"));
                assertEquals(1, savedReport.get("totalRuns"));
            }
        }
    }

    @Test
    void testPruneOldEvents(@TempDir Path tempDir) throws Exception {
        EventStoreConfig config = EventStoreConfig.withDbPath(tempDir.resolve("prune_db.db"));
        try (SqliteEventStore eventStore = new SqliteEventStore(config);
             RunManager manager = new RunManager(eventStore)) {

            // Append two events
            eventStore.append("run-1", new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.BAR, Instant.now(), "run-1",
                "LondonOpenRangeBreakout", "EUR_USD", "BACKTEST", java.util.Map.of()
            ));
            eventStore.append("run-1", new RunEvent(
                RunEvent.SCHEMA_VERSION, RunEventType.BAR, Instant.now(), "run-1",
                "LondonOpenRangeBreakout", "EUR_USD", "BACKTEST", java.util.Map.of()
            ));

            // Manually modify the created_at timestamp of the first event to 35 days ago
            try (java.sql.Statement stmt = eventStore.connection().createStatement()) {
                stmt.executeUpdate("UPDATE events SET created_at = '2026-05-20T12:00:00Z' WHERE sequence = 1");
                try (java.sql.ResultSet rs = stmt.executeQuery("SELECT sequence, created_at, datetime(created_at), datetime('now', '-30 days') FROM events")) {
                    while (rs.next()) {
                        System.out.println("DEBUG: seq=" + rs.getInt(1) + " raw=" + rs.getString(2) + " parsed=" + rs.getString(3) + " threshold=" + rs.getString(4));
                    }
                }
            }

            // Verify both still exist initially
            assertEquals(2, eventStore.count("run-1"));

            System.setProperty("user.home", tempDir.toString());

            // Run reconciliation and verify one event was pruned
            try (DailyReconciliationService service = new DailyReconciliationService(manager)) {
                Map<String, Object> report = service.runReconciliation();
                assertEquals(1, report.get("prunedEventsCount"));

                // Verify that only the recent event remains
                assertEquals(1, eventStore.count("run-1"));
            }
        }
    }
}
