package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentStoreTest {

    @Test
    void inMemory_storesDistinctBrokerAccountsPerStrategy() {
        try (InMemoryDeploymentStore store = new InMemoryDeploymentStore()) {
            store.save(new DeploymentRecord(
                "StrategyA",
                RunMode.PAPER,
                Instant.parse("2024-01-01T00:00:00Z"),
                "run-a",
                List.of(),
                ExecutionLabel.PAPER_OANDA,
                "firm-a"));
            store.save(new DeploymentRecord(
                "StrategyB",
                RunMode.PAPER,
                Instant.parse("2024-01-02T00:00:00Z"),
                "run-b",
                List.of(),
                ExecutionLabel.PAPER_OANDA,
                "firm-b"));

            assertEquals("firm-a", store.get("StrategyA").orElseThrow().brokerAccountId());
            assertEquals("firm-b", store.get("StrategyB").orElseThrow().brokerAccountId());
            assertTrue(store.get("StrategyA").orElseThrow().toMap().containsKey("brokerAccountId"));
        }
    }

    @Test
    void sqlite_persistsBrokerAccountId(@TempDir Path tempDir) {
        EventStoreConfig config = EventStoreConfig.withDbPath(tempDir.resolve("runtime.db"));
        try (SqliteDeploymentStore store = new SqliteDeploymentStore(config)) {
            store.save(new DeploymentRecord(
                "LondonOpenRangeBreakout",
                RunMode.PAPER,
                Instant.parse("2024-03-01T00:00:00Z"),
                "run-1",
                List.of(),
                ExecutionLabel.PAPER_OANDA,
                "prop-main"));
        }
        try (SqliteDeploymentStore store = new SqliteDeploymentStore(config)) {
            DeploymentRecord loaded = store.get("LondonOpenRangeBreakout").orElseThrow();
            assertEquals("prop-main", loaded.brokerAccountId());
            assertEquals(ExecutionLabel.PAPER_OANDA, loaded.executionLabel());
        }
    }
}
