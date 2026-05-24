package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventHubTest {

    @Test
    void publish_deliversToSubscriber() {
        RunEventHub hub = new RunEventHub();
        List<String> messages = new CopyOnWriteArrayList<>();
        hub.subscribe("run-a", messages::add);

        RunEvent event = sampleEvent("run-a", RunEventType.RUN_STARTED);
        hub.publish("run-a", new StoredRunEvent(1L, event));

        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("\"sequence\":1"));
        assertTrue(messages.getFirst().contains("RUN_STARTED"));
    }

    @Test
    void publish_isolatedByRunId() {
        RunEventHub hub = new RunEventHub();
        List<String> runA = new ArrayList<>();
        List<String> runB = new ArrayList<>();
        hub.subscribe("run-a", runA::add);
        hub.subscribe("run-b", runB::add);

        hub.publish("run-a", new StoredRunEvent(1L, sampleEvent("run-a", RunEventType.RUN_STARTED)));

        assertEquals(1, runA.size());
        assertEquals(0, runB.size());
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
