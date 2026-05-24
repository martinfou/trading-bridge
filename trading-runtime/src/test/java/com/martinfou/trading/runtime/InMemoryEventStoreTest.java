package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class InMemoryEventStoreTest {

    @Test
    void append_assignsMonotonicGlobalSequences() {
        EventStore store = EventStores.inMemory();
        RunEvent e1 = sampleEvent("run-a", RunEventType.RUN_STARTED);
        RunEvent e2 = sampleEvent("run-b", RunEventType.RUN_ENDED);

        long seq1 = store.append("run-a", e1);
        long seq2 = store.append("run-b", e2);

        assertEquals(1, seq1);
        assertEquals(2, seq2);
    }

    @Test
    void query_paginatesBySequence() {
        EventStore store = EventStores.inMemory();
        store.append("run-a", sampleEvent("run-a", RunEventType.RUN_STARTED));
        store.append("run-a", sampleEvent("run-a", RunEventType.RUN_ENDED));
        store.append("run-b", sampleEvent("run-b", RunEventType.RUN_STARTED));

        assertEquals(2, store.query("run-a", 0, 10).size());
        assertEquals(1, store.query("run-a", 1, 10).size());
        assertEquals(0, store.query("run-a", 2, 10).size());
        assertEquals(1, store.query("run-a", 0, 1).size());
    }

    @Test
    void count_and_replayAll_areRunScoped() {
        EventStore store = EventStores.inMemory();
        store.append("run-a", sampleEvent("run-a", RunEventType.RUN_STARTED));
        store.append("run-a", sampleEvent("run-a", RunEventType.RUN_ENDED));
        store.append("run-b", sampleEvent("run-b", RunEventType.ERROR));

        assertEquals(2, store.count("run-a"));
        assertEquals(1, store.count("run-b"));
        assertEquals(0, store.count("missing"));

        assertEquals(2, store.replayAll("run-a").size());
        assertEquals(RunEventType.RUN_STARTED, store.replayAll("run-a").getFirst().type());
        assertEquals(RunEventType.RUN_ENDED, store.replayAll("run-a").get(1).type());
    }

    @Test
    void validation_rejectsInvalidInput() {
        EventStore store = EventStores.inMemory();
        RunEvent event = sampleEvent("run-a", RunEventType.RUN_STARTED);

        assertThrows(IllegalArgumentException.class, () -> store.append(null, event));
        assertThrows(IllegalArgumentException.class, () -> store.append("  ", event));
        assertThrows(IllegalArgumentException.class, () -> store.append("run-a", null));
        assertThrows(IllegalArgumentException.class, () -> store.query("run-a", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> store.query(null, 0, 1));
    }

    @Test
    void replayAll_matchesQueryWithMaxLimit() {
        EventStore store = EventStores.inMemory();
        for (int i = 0; i < 5; i++) {
            store.append("run-a", sampleEvent("run-a", RunEventType.RUN_STARTED));
        }
        assertEquals(store.query("run-a", 0, 100).size(), store.replayAll("run-a").size());
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
