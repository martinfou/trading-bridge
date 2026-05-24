package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BroadcastingEventStoreTest {

    @Test
    void append_broadcastsToHub() {
        RunEventHub hub = new RunEventHub();
        List<String> messages = new CopyOnWriteArrayList<>();
        hub.subscribe("run-a", messages::add);

        try (EventStore store = new BroadcastingEventStore(EventStores.inMemory(), hub)) {
            store.append("run-a", sampleEvent(RunEventType.RUN_STARTED));
            store.append("run-a", sampleEvent(RunEventType.RUN_ENDED));
        }

        assertEquals(2, messages.size());
        assertEquals(2, messages.stream().filter(m -> m.contains("RUN_STARTED") || m.contains("RUN_ENDED")).count());
    }

    private static RunEvent sampleEvent(RunEventType type) {
        return new RunEvent(
            RunEvent.SCHEMA_VERSION,
            type,
            Instant.parse("2026-05-23T12:00:00Z"),
            "run-a",
            "LondonOpenRangeBreakout",
            "EUR_USD",
            RunMode.BACKTEST.name(),
            Map.of());
    }
}
