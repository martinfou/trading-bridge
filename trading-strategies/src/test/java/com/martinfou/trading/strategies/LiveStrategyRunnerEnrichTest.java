package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the enrichment of the aggregated monitor with the fields the hub needs
 * for snapshot ingestion. Exercises the existing accessors ({@code getActiveTrades},
 * {@code getLastHeartbeatTime}) rather than touching any new state.
 */
class LiveStrategyRunnerEnrichTest {

    private static class NoopStrategy implements Strategy {
        @Override public String name() { return "Noop"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return new ArrayList<>(); }
        @Override public void reset() { }
    }

    @AfterEach
    void cleanup() {
        LiveStrategyRunner.getActiveRunners().clear();
    }

    @Test
    void enrichAddsSnapshotIdAccountIdHeartbeatAndOpenPositions() throws Exception {
        LiveStrategyRunner runner = new LiveStrategyRunner(
            "key", "101-004-123", new NoopStrategy(), "consecbar", "H1", 60);
        LiveStrategyRunner.getActiveRunners().put("consecbar", runner);

        runner.getActiveTrades().add(new LiveStrategyRunner.ActiveTrade(
            "42", "GBP_JPY", "BUY", 180.5, 1000, 179.0, 185.0,
            Instant.parse("2026-09-30T12:00:00Z")));

        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("timestamp", "2026-09-30T12:30:00Z");
        LiveStrategyRunner.enrichAggregatedMonitor(root);

        assertEquals("101-004-123", root.path("account_id").asText());
        assertEquals("101-004-123:2026-09-30T12:30:00Z", root.path("snapshot_id").asText());
        assertEquals(runner.getLastHeartbeatTime().toString(), root.path("heartbeat_at").asText());

        var openPositions = root.path("open_positions");
        assertEquals(1, openPositions.size());
        assertEquals("consecbar", openPositions.get(0).path("strategy").asText());
        assertEquals("42", openPositions.get(0).path("tradeId").asText());
        assertEquals("GBP_JPY", openPositions.get(0).path("symbol").asText());
        assertEquals("BUY", openPositions.get(0).path("side").asText());
    }

    @Test
    void enrichProducesEmptyPositionsWhenNoActiveTrades() throws Exception {
        LiveStrategyRunner runner = new LiveStrategyRunner(
            "key", "101-004-123", new NoopStrategy(), "consecbar", "H1", 60);
        LiveStrategyRunner.getActiveRunners().put("consecbar", runner);

        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("timestamp", "2026-09-30T12:30:00Z");
        LiveStrategyRunner.enrichAggregatedMonitor(root);

        assertEquals(0, root.path("open_positions").size());
        assertEquals(0, root.path("unreconciled_trades").size());
        assertTrue(root.hasNonNull("heartbeat_at"));
    }
}
