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
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastingEventStoreTest {

    @Test
    void persistBeforeBroadcast_subscriberSeesStoredEvent() {
        RunEventHub hub = new RunEventHub();
        TrackingStore tracking = new TrackingStore(EventStores.inMemory());

        hub.subscribe("run-a", json ->
            assertTrue(tracking.persistedBeforeBroadcast, "event must be persisted before broadcast"));

        try (EventStore store = new BroadcastingEventStore(tracking, hub)) {
            store.append("run-a", sampleEvent(RunEventType.RUN_STARTED));
        }

        assertTrue(tracking.persistedBeforeBroadcast);
    }

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

    private static final class TrackingStore implements EventStore {
        private final EventStore delegate;
        private boolean persistedBeforeBroadcast;

        TrackingStore(EventStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public long append(String runId, RunEvent event) {
            long seq = delegate.append(runId, event);
            persistedBeforeBroadcast = delegate.count(runId) > 0;
            return seq;
        }

        @Override
        public List<RunEvent> query(String runId, long afterSequence, int limit) {
            return delegate.query(runId, afterSequence, limit);
        }

        @Override
        public long count(String runId) {
            return delegate.count(runId);
        }

        @Override
        public List<RunEvent> replayAll(String runId) {
            return delegate.replayAll(runId);
        }

        @Override
        public List<StoredRunEvent> queryWithSequence(String runId, long afterSequence, int limit) {
            return delegate.queryWithSequence(runId, afterSequence, limit);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
