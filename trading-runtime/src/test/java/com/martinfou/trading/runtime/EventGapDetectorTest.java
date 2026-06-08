package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventGapDetectorTest {

    @Test
    void analyze_reportsNoGapsForContiguousSequence() {
        try (EventStore store = EventStores.inMemory()) {
            store.append("run-a", sample("run-a", RunEventType.RUN_STARTED));
            store.append("run-a", sample("run-a", RunEventType.RUN_ENDED));

            EventGapDetector.Result result = EventGapDetector.analyze("run-a", store);
            assertEquals(2, result.eventCount());
            assertEquals(2, result.maxSequence());
            assertTrue(result.gaps().isEmpty());
            assertTrue(result.complete());
        }
    }

    @Test
    void analyze_reportsGapWhenSequenceSkipped() {
        EventStore store = new StubEventStore(
            List.of(
                new StoredRunEvent(1, sample("run-b", RunEventType.RUN_STARTED)),
                new StoredRunEvent(3, sample("run-b", RunEventType.ERROR))));

        EventGapDetector.Result result = EventGapDetector.analyze("run-b", store);
        assertFalse(result.complete());
        assertEquals(1, result.gaps().size());
        assertEquals(2, result.gaps().getFirst().fromSequence());
        assertEquals(2, result.gaps().getFirst().toSequence());
    }

    private static RunEvent sample(String runId, RunEventType type) {
        return new RunEvent(
            RunEvent.SCHEMA_VERSION,
            type,
            Instant.parse("2026-05-24T12:00:00Z"),
            runId,
            "LondonOpenRangeBreakout",
            "EUR_USD",
            RunMode.BACKTEST.name(),
            Map.of());
    }

    private static final class StubEventStore implements EventStore {
        private final List<StoredRunEvent> events;

        StubEventStore(List<StoredRunEvent> events) {
            this.events = events;
        }

        @Override
        public long append(String runId, RunEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RunEvent> query(String runId, long afterSequence, int limit) {
            return queryWithSequence(runId, afterSequence, limit).stream().map(StoredRunEvent::event).toList();
        }

        @Override
        public long count(String runId) {
            return events.size();
        }

        @Override
        public List<RunEvent> replayAll(String runId) {
            return query(runId, 0, Integer.MAX_VALUE);
        }

        @Override
        public List<StoredRunEvent> queryWithSequence(String runId, long afterSequence, int limit) {
            return events.stream()
                .filter(e -> e.sequence() > afterSequence)
                .limit(limit)
                .toList();
        }

        @Override
        public List<String> listAllRunIds() {
            return events.stream().map(e -> e.event().runId()).distinct().toList();
        }

        @Override
        public void close() {
        }
    }
}
